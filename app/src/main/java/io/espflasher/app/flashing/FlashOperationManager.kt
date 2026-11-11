package io.espflasher.app.flashing

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import io.espflasher.app.protocol.ChipType
import io.espflasher.app.protocol.Compressor
import io.espflasher.app.protocol.EspCommand
import io.espflasher.app.protocol.EspRomLoader
import io.espflasher.app.protocol.FlashLayout
import io.espflasher.app.protocol.FlasherException
import io.espflasher.app.protocol.ImageHeader
import io.espflasher.app.protocol.PartitionTable
import io.espflasher.app.protocol.ResetSequences
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.UsbSerialManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/**
 * The single entry point the ViewModel drives for every button on the main
 * screen. Owns the currently-open [io.espflasher.app.usb.UsbSerialPort] and
 * [EspRomLoader], and translates each high-level operation (Detect, Erase,
 * Flash, Verify, Read) into the right sequence of protocol calls, reporting
 * progress and console output through [FlashCallback] rather than touching
 * any UI/StateFlow type directly.
 *
 * Cancellation (the Stop button): callers run these suspend functions in a
 * cancellable coroutine `Job`. Block-write loops check
 * `currentCoroutineContext().ensureActive()` every block (a few KB), so Stop
 * takes effect almost immediately during the bulk of any transfer; it can
 * take a couple of seconds longer if cancelled while waiting on a single
 * long call (e.g. a multi-megabyte erase-timeout window), which is an
 * inherent limitation of interrupting blocking USB bulk I/O.
 */
class FlashOperationManager(
    private val context: Context,
    private val usbSerialManager: UsbSerialManager
) {
    companion object {
        /** Default suggestion shown in the Upload Baud Rate picker - matches esptool's common --baud 921600. */
        const val FAST_BAUD_RATE = 921600
    }

    /**
     * Off by default - this app's whole design is ROM-only (see
     * [io.espflasher.app.protocol.StubLoader]'s doc comment for why, and
     * what turning this on trades off). Settable from the UI; read fresh
     * at the start of every [detectDevice] call, so toggling it takes
     * effect on the next Detect, not retroactively on an already-open
     * session.
     */
    var useStubLoader: Boolean = false

    private val stubLoader: io.espflasher.app.protocol.StubLoader =
        io.espflasher.app.protocol.EspressifStubLoader { name -> runCatching { context.assets.open(name) }.getOrNull() }

    private var port: io.espflasher.app.usb.UsbSerialPort? = null
    private var loader: EspRomLoader? = null

    /**
     * Set at the start of [writeImages]/[verifyImages] from whatever the
     * person has typed into the (collapsed-by-default) Credentials panel -
     * see [BinaryCredentialPatcher]. Read/erase/detect never touch this;
     * only [readAllBytes], via write and verify.
     */
    private var credentials: FirmwareCredentials = FirmwareCredentials()

    var detectedChip: ChipType? = null
        private set
    var detectedFlashSizeBytes: Long? = null
        private set

    /**
     * True only right after a successful sync - i.e. the chip is confirmed
     * to actually be sitting in the ROM bootloader listening for commands
     * right now. Knowing the chip *type* from an earlier Detect doesn't mean
     * it's still there: a Reset (intentional, e.g. after flashing) boots it
     * into the app instead, and the next operation needs a fresh sync before
     * trusting the connection again, not just the cached chip type.
     */
    private var inBootloader: Boolean = false

    val isConnected: Boolean get() = port?.isOpen == true
    val connectedDeviceInfo get() = port?.info
    /** Lets [io.espflasher.app.ameba.AmebaOperationManager] reuse the same open USB connection - BW16 and ESP32 both connect the same way, only the protocol spoken over the wire differs. */
    val currentPort: io.espflasher.app.usb.UsbSerialPort? get() = port

    // -----------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------

    suspend fun connect(device: UsbDevice, config: SerialConfig, callback: FlashCallback) {
        if (!usbSerialManager.hasPermission(device)) {
            callback.onLog(LogLevel.INFO, "Requesting USB permission...")
            val granted = usbSerialManager.requestPermission(device)
            if (!granted) throw FlasherException.PermissionDenied(device.deviceName)
        }
        val newPort = usbSerialManager.buildPort(device, config)
        newPort.open()
        port = newPort
        loader = EspRomLoader(newPort)
        detectedChip = null
        detectedFlashSizeBytes = null
        inBootloader = false
        callback.onLog(
            LogLevel.SUCCESS,
            "Connected to ${newPort.info.displayLabel} (${newPort.info.vidPidHex}) at ${config.baudRate} baud"
        )
    }

    suspend fun disconnect(callback: FlashCallback) {
        runCatching { port?.close() }
        port = null
        loader = null
        detectedChip = null
        detectedFlashSizeBytes = null
        inBootloader = false
        callback.onLog(LogLevel.INFO, "Disconnected")
    }

    suspend fun resetDevice(callback: FlashCallback) {
        val p = port ?: throw FlasherException.UsbDisconnected()
        ResetSequences.resetToRun(p)
        inBootloader = false
        callback.onLog(LogLevel.INFO, "Reset - device is now running its flashed application")
    }

    suspend fun enterBootloaderManual(callback: FlashCallback) {
        val p = port ?: throw FlasherException.UsbDisconnected()
        ResetSequences.enterBootloader(p)
        callback.onLog(LogLevel.INFO, "Reset into the UART download bootloader")
    }

    // -----------------------------------------------------------------
    // Detect
    // -----------------------------------------------------------------

    suspend fun detectDevice(callback: FlashCallback): DeviceInfoSnapshot {
        val ld = requireLoader()
        val p = port ?: throw FlasherException.UsbDisconnected()

        callback.onLog(LogLevel.INFO, "Resetting into bootloader...")
        ResetSequences.enterBootloader(p)
        callback.onLog(LogLevel.COMMAND, "-> SYNC")
        var synced = ld.sync()
        if (!synced) {
            callback.onLog(LogLevel.WARNING, "No response - retrying bootloader entry...")
            ResetSequences.enterBootloader(p)
            synced = ld.sync()
        }
        if (!synced) throw FlasherException.SyncFailed(7)
        callback.onLog(LogLevel.SUCCESS, "<- Sync OK")

        val chip = ld.detectChip()
        detectedChip = chip
        inBootloader = true
        callback.onLog(LogLevel.SUCCESS, "Chip: ${chip.displayName}")

        if (useStubLoader) {
            val started = stubLoader.start(ld) { msg -> callback.onLog(LogLevel.DEBUG, msg) }
            if (started) {
                callback.onLog(LogLevel.SUCCESS, "Stub flasher running.")
            } else {
                callback.onLog(
                    LogLevel.WARNING,
                    "Stub flasher unavailable for ${chip.displayName} - continuing ROM-only."
                )
            }
        } else {
            // A hardware reset (which is exactly what just happened, right
            // above, to get a clean ROM sync) wipes anything previously
            // uploaded to RAM - if a stub was running from an earlier
            // operation and the checkbox is now off, that stub no longer
            // exists on the actual chip, so this state must not keep
            // claiming otherwise.
            stubLoader.reset()
        }

        if (chip == ChipType.ESP8266 && !stubLoader.isRunning) {
            // ESP8266's ROM has no SPI_ATTACH command at all - esptool attaches
            // flash as a side effect of a zero-size flash_begin() instead.
            // (SPI_ATTACH itself only exists once a stub is running - same
            // stub-only gate as compression and baud change - so once the
            // stub above is active, ESP8266 gets the real command too.)
            runCatching { ld.flashBegin(chip, 0, 0, isStub = false) }.onFailure {
                callback.onLog(LogLevel.WARNING, "SPI attach (via zero-size flash_begin) failed: ${it.message}")
            }
        } else {
            ld.spiAttach()
        }

        val mac = runCatching { ld.readMac(chip) }.getOrElse {
            callback.onLog(LogLevel.WARNING, "Could not read MAC address: ${it.message}")
            "Unknown"
        }
        callback.onLog(LogLevel.INFO, "MAC address: $mac")

        val flashId = runCatching { ld.readFlashId(chip) }.getOrElse {
            callback.onLog(LogLevel.WARNING, "Could not read flash ID: ${it.message}")
            null
        }
        val flashSize = flashId?.sizeBytes
        detectedFlashSizeBytes = flashSize
        if (flashSize != null) {
            callback.onLog(LogLevel.INFO, "Flash: ${FlashLayout.humanReadableSize(flashSize)} " +
                "(mfr=0x%02X, memType=0x%02X)".format(flashId.manufacturer, flashId.memoryType))
            if (chip != ChipType.ESP8266 || stubLoader.isRunning) {
                // ESP8266's ROM has no SPI_SET_PARAMS support at all, but the
                // stub does - matches esptool's own ESP8266ROM override:
                // `if self.IS_STUB: super().flash_set_parameters(size)`. The
                // ROM-only case silently skips this (matches the comment this
                // replaced), but the stub case must not: this was skipping it
                // unconditionally for every ESP8266 session, including once a
                // stub was running and fully able to use it - leaving the
                // stub without the flash size/geometry (SPI_SET_PARAMS also
                // carries block/sector/page size) it was built expecting to
                // have before any erase or write.
                runCatching { ld.setSpiFlashParameters(flashSize) }.onFailure {
                    callback.onLog(LogLevel.WARNING, "Could not set SPI flash parameters: ${it.message}")
                }
            }
        } else {
            callback.onLog(LogLevel.WARNING, "Flash size unknown - erase/write may fail until it's detected.")
        }

        val security = if (chip.supportsSecurityInfo) runCatching { ld.getSecurityInfo() }.getOrNull() else null

        return DeviceInfoSnapshot(
            chip = chip,
            revision = "Not exposed via ROM-only mode",
            macAddress = mac,
            crystalFreqMHz = 40,
            flashId = flashId,
            flashSizeBytes = flashSize,
            flashMode = null,
            features = chipFeatures(chip),
            securityInfo = security,
            stubRunning = stubLoader.isRunning
        )
    }

    /**
     * Reads the partition table straight off the connected device and
     * parses it - the on-device equivalent of picking a local
     * `partition-table.bin` file (see [io.espflasher.app.protocol.PartitionTable]),
     * for when you don't have that file handy but do have the board in
     * front of you. This is what a desktop tool's "Check SPIFFS" (running
     * `parttool.py --port ... get_partition_info`) is really doing under
     * the hood - this app now has the same stub-based read that relies on.
     *
     * Uses the stub's [EspRomLoader.readFlashStub] when one is running;
     * otherwise only works on classic ESP32, since every other chip's ROM
     * has no flash-read capability whatsoever without a stub (see
     * [EspRomLoader.supportsRomOnlyRead]) - for those chips without a stub
     * running, this throws a clear error pointing at the local-file path
     * instead of hanging or failing mysteriously.
     */
    suspend fun readPartitionTable(callback: FlashCallback): PartitionTable {
        val (ld, chip) = ensureReady(callback)
        if (!stubLoader.isRunning && !ld.supportsRomOnlyRead(chip)) {
            throw FlasherException.Generic(
                "${chip.displayName}'s ROM can't read flash back without a stub loader, so the partition table can't be " +
                    "read from the device directly. Turn on \"use stub loader\" under Advanced and run Detect again, or " +
                    "pick your locally-built partition-table.bin file instead - the SPIFFS offset gets filled in the " +
                    "exact same way either way."
            )
        }
        callback.onLog(LogLevel.COMMAND, "Reading partition table from 0x%X...".format(chip.partitionTableOffset))
        // CONFIG_PARTITION_TABLE_MAX_LEN's default - one full flash sector
        val bytes = if (stubLoader.isRunning) {
            ld.readFlashStub(chip.partitionTableOffset, 0x1000L)
        } else {
            ld.readFlashRomOnly(chip.partitionTableOffset, 0x1000L)
        }
        return PartitionTable.parse(bytes)
    }

    // -----------------------------------------------------------------
    // Erase
    // -----------------------------------------------------------------

    suspend fun eraseFlash(resetAfter: Boolean, callback: FlashCallback) {
        val (ld, chip) = ensureReady(callback)
        val size = detectedFlashSizeBytes
            ?: throw FlasherException.Generic("Flash size unknown - run Detect first, or the flash ID couldn't be read.")
        if (stubLoader.isRunning) {
            // The real, hardware-level erase command - only exists once a
            // stub is running (see EspRomLoader.eraseFlashStub's doc
            // comment). No data transfer at all, so it's not scoped to a
            // region the way eraseCore's write-workaround is - it always
            // erases the whole chip, which is exactly what this function
            // wants anyway.
            callback.onLog(LogLevel.COMMAND, "Erasing flash (stub) - this can take a while...")
            ld.eraseFlashStub()
            callback.onLog(LogLevel.SUCCESS, "Flash erased.")
        } else {
            eraseCore(ld, chip, offset = 0L, size = size, label = "Erasing flash", callback = callback)
        }
        if (resetAfter) resetDevice(callback)
    }

    /**
     * Erase just one region of flash - e.g. a single partition - rather than
     * the whole chip. Same "write 0xFF across the range" workaround as
     * [eraseFlash] (see the comment on [eraseCore]), just scoped down. Handy
     * for wiping/regenerating something like a SPIFFS or LittleFS partition
     * without touching NVS, OTA data, or the app itself.
     */
    suspend fun erasePartition(offset: Long, size: Long, label: String, resetAfter: Boolean, callback: FlashCallback) {
        if (offset < 0) throw FlasherException.InvalidAddress(offset)
        if (size <= 0) throw FlasherException.Generic("Nothing to erase - $label has a size of zero.")
        val (ld, chip) = ensureReady(callback)
        detectedFlashSizeBytes?.let { flashSize ->
            if (offset + size > flashSize) {
                throw FlasherException.Generic(
                    "$label (0x%X, %s) runs past the end of the detected %s flash - refusing to erase it."
                        .format(offset, FlashLayout.humanReadableSize(size), FlashLayout.humanReadableSize(flashSize))
                )
            }
        }
        eraseCore(ld, chip, offset, size, "Erasing $label", callback)
        if (resetAfter) resetDevice(callback)
    }

    /** The actual erase - deliberately never touches baud rate; it always runs at whatever the connection is already at. */
    private suspend fun eraseCore(ld: EspRomLoader, chip: ChipType, offset: Long, size: Long, label: String, callback: FlashCallback) {
        // The ROM has no dedicated "erase without writing" command outside a
        // stub loader - flash_begin's up-front erase only takes effect once
        // you actually complete the block sequence it expects; skipping
        // straight to flash_end after a bare begin gets rejected. So "erase"
        // here means writing 0xFF across the region instead: flash_begin
        // already blanks every sector it covers, so this simply confirms
        // that blank state through the same write path already proven to
        // work, and since the buffer is one repeated byte, it compresses to
        // a few KB regardless of region size - the erase is what's slow here,
        // not the transfer (and on ESP8266, transferToFlash transparently
        // falls back to an uncompressed transfer, which is slower still for
        // a full-chip erase but the only option that chip's ROM supports).
        callback.onLog(LogLevel.COMMAND, "$label (0x%X, %s) - this can take a while...".format(offset, FlashLayout.humanReadableSize(size)))
        val blank = ByteArray(size.toInt())
        blank.fill(0xFF.toByte())
        transferToFlash(ld, chip, blank, offset, compress = true, skipChecksum = false, label = label, callback = callback)
        callback.onLog(LogLevel.SUCCESS, "$label complete.")
    }

    // -----------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------

    suspend fun writeImages(
        images: List<FlashImage>,
        options: FlashOptions,
        uploadBaudRate: Int,
        callback: FlashCallback,
        credentials: FirmwareCredentials = FirmwareCredentials()
    ) {
        if (images.isEmpty()) throw FlasherException.Generic("No files selected to flash.")
        this.credentials = credentials
        val (ld, chip) = ensureReady(callback)
        val p = port ?: throw FlasherException.UsbDisconnected()
        val sorted = images.sortedBy { it.offset }

        // Erase (if requested) runs at whatever baud the connection already
        // is - same as the standalone Erase button. Only once it's time to
        // actually upload firmware does baud switch to the user's chosen
        // rate, and it drops back down again - while the chip is still
        // confirmed in the bootloader, so the revert command is guaranteed
        // to be heard - as soon as the upload (and any verify right after
        // it) finishes. Reset happens last, after baud is already back to
        // normal: it's a DTR/RTS pulse, not a UART command, so its
        // correctness never depended on baud anyway, but doing it last
        // keeps the ordering easy to reason about.
        if (options.eraseBeforeWrite) {
            val size = detectedFlashSizeBytes
                ?: throw FlasherException.Generic("Flash size unknown - run Detect first, or the flash ID couldn't be read.")
            eraseCore(ld, chip, offset = 0L, size = size, label = "Erasing flash", callback = callback)
        }

        withFastBaud(ld, p, chip, uploadBaudRate, callback) {
            for (image in sorted) {
                currentCoroutineContext().ensureActive()
                writeSingleImage(ld, chip, image, options, callback)
            }
            if (options.verifyAfterWrite) {
                verifyCore(ld, chip, sorted, callback)
            }
        }

        if (options.resetAfterFlashing) {
            resetDevice(callback)
        }
    }

    private suspend fun writeSingleImage(
        ld: EspRomLoader,
        chip: ChipType,
        image: FlashImage,
        options: FlashOptions,
        callback: FlashCallback
    ) {
        if (image.offset < 0) throw FlasherException.InvalidAddress(image.offset)

        callback.onLog(LogLevel.INFO, "Reading ${image.fileName}...")
        val data = readAllBytes(image.uri, chip, callback)

        if (image.requiresImageMagic && !ImageHeader.looksValid(data)) {
            callback.onLog(
                LogLevel.WARNING,
                "${image.fileName} doesn't start with the ESP image magic byte (0xE9) - could be the wrong " +
                    "file for the ${image.label} slot, or it could be a merged/combined image, filesystem " +
                    "image, or other raw payload that's fine to flash as-is. Proceeding."
            )
        }

        transferToFlash(ld, chip, data, image.offset, options.compressData, options.skipChecksum, "Writing ${image.label}", callback)
        callback.onLog(LogLevel.SUCCESS, "Wrote ${image.label}: ${data.size} bytes at 0x%X".format(image.offset))
    }

    /**
     * The one place that actually streams bytes to flash via flash_begin,
     * flash_data, and flash_end (or the compressed _defl_ variants). Used for
     * both real file writes and for [eraseFlash]'s all-0xFF pass, so there's
     * exactly one implementation of the block-chunking/checksum/progress
     * logic to get right.
     */
    private suspend fun transferToFlash(
        ld: EspRomLoader,
        chip: ChipType,
        data: ByteArray,
        offset: Long,
        compress: Boolean,
        skipChecksum: Boolean,
        label: String,
        callback: FlashCallback
    ) {
        // ESP8266's ROM has no FLASH_DEFL_* opcodes at all (see
        // ChipType.supportsCompressedWrite) - asking for it there doesn't
        // fail cleanly, it just hangs until the caller's own timeout fires.
        // Every other supported chip's ROM does support it, so this only
        // ever downgrades on ESP8266 - unless a stub is running, which
        // implements compression on every chip, ESP8266 included.
        val useCompression = compress && (chip.supportsCompressedWrite || stubLoader.isRunning)
        if (compress && !useCompression) {
            callback.onLog(
                LogLevel.INFO,
                "${chip.displayName}'s ROM bootloader doesn't support compressed writes - using an uncompressed transfer instead."
            )
        }
        callback.onLog(LogLevel.COMMAND, "flash_begin size=0x%X offset=0x%X compress=%s".format(data.size, offset, useCompression))
        val checksumOverride = if (skipChecksum) 0 else null

        if (useCompression) {
            val compressed = Compressor.zlibCompress(data)
            callback.onLog(LogLevel.DEBUG, "Compressed ${data.size} -> ${compressed.size} bytes")
            ld.flashDeflBegin(chip, data.size.toLong(), compressed.size.toLong(), offset, isStub = stubLoader.isRunning)
            val tracker = ProgressTracker(compressed.size.toLong(), label)
            var seq = 0
            var pos = 0
            while (pos < compressed.size) {
                currentCoroutineContext().ensureActive()
                val len = minOf(EspCommand.FLASH_WRITE_SIZE, compressed.size - pos)
                val block = compressed.copyOfRange(pos, pos + len)
                ld.flashDeflBlock(block, seq, checksumOverride = checksumOverride)
                pos += len
                seq++
                val estimatedAddr = offset + (pos.toLong() * data.size / compressed.size.coerceAtLeast(1))
                callback.onProgress(tracker.update(pos.toLong(), estimatedAddr))
            }
        } else {
            ld.flashBegin(chip, data.size.toLong(), offset, isStub = stubLoader.isRunning)
            val tracker = ProgressTracker(data.size.toLong(), label)
            var seq = 0
            var pos = 0
            while (pos < data.size) {
                currentCoroutineContext().ensureActive()
                val remaining = data.size - pos
                val len = minOf(EspCommand.FLASH_WRITE_SIZE, remaining)
                val block = if (len == EspCommand.FLASH_WRITE_SIZE) {
                    data.copyOfRange(pos, pos + len)
                } else {
                    ByteArray(EspCommand.FLASH_WRITE_SIZE) { i -> if (i < len) data[pos + i] else 0xFF.toByte() }
                }
                ld.flashBlock(block, seq, checksumOverride = checksumOverride)
                pos += len
                seq++
                callback.onProgress(tracker.update(pos.toLong().coerceAtMost(data.size.toLong()), offset + pos))
            }
        }

        // A stub write acks each block the instant it's *received*, not once
        // it's actually landed in flash - the physical SPI write happens
        // asynchronously while the next block is already arriving, which is
        // exactly what makes stub writes fast (no waiting out each SPI
        // write's own latency between blocks). The cost is that the last
        // block's ack does NOT mean the last write finished - flash_end
        // sent right behind it can arrive while that write is still
        // in-flight. esptool's own write_flash() covers exactly this,
        // right after its own write loop and before flash_finish/
        // flash_defl_finish, with:
        //     esp.read_reg(ESPLoader.CHIP_DETECT_MAGIC_REG_ADDR, timeout=timeout)
        // read_reg() has nothing to do with the register's value here - the
        // point is that the stub processes commands strictly in order, so
        // it can't answer a *new* command until it's done draining
        // whatever flash write the last block queued.
        //
        // flash_finish/flash_defl_end are gated to stub sessions entirely,
        // not just this drain step - esptool's own write_flash() never
        // sends FLASH_END to the ROM at all, anywhere, and says exactly
        // why right at the call site (this is a direct quote, not a
        // paraphrase):
        //     if esp.IS_STUB:
        //         # skip sending flash_finish to ROM loader here,
        //         # as it causes the loader to exit and run user code
        //         esp.flash_begin(0, 0)
        //         ...
        //         esp.flash_finish(False)
        // The ROM path's block writes are already synchronous (each ack
        // means "physically written" - see the drain-barrier comment
        // above), so there's nothing left for flash_finish to accomplish
        // there anyway; sending it just gives the ROM's flash_end handler
        // a command it isn't meant to receive mid-session, which is very
        // plausibly the exact "status=0x01 reason=0x06 ... flash end"
        // rejection seen in testing on ESP8266 ROM-only writes. Resetting
        // into the flashed app afterward already happens through a
        // completely separate path - resetDevice()'s DTR/RTS pulse, not
        // this command's reboot flag - so skipping it here costs nothing.
        if (stubLoader.isRunning) {
            ld.readReg(EspCommand.CHIP_DETECT_MAGIC_REG_ADDR)
            ld.flashFinish(reboot = false, compressed = useCompression)
        }
        callback.onProgress(ProgressState.IDLE)
    }

    // -----------------------------------------------------------------
    // Verify
    // -----------------------------------------------------------------

    suspend fun verifyImages(images: List<FlashImage>, callback: FlashCallback, credentials: FirmwareCredentials = FirmwareCredentials()) {
        if (images.isEmpty()) throw FlasherException.Generic("No files selected to verify.")
        this.credentials = credentials
        val (ld, chip) = ensureReady(callback)
        verifyCore(ld, chip, images, callback)
    }

    private suspend fun verifyCore(ld: EspRomLoader, chip: ChipType, images: List<FlashImage>, callback: FlashCallback) {
        for (image in images.sortedBy { it.offset }) {
            currentCoroutineContext().ensureActive()
            callback.onLog(LogLevel.INFO, "Verifying ${image.label} (${image.fileName})...")
            val data = readAllBytes(image.uri, chip, callback)
            val localMd5 = md5Hex(data)

            // esptool's own flash_md5sum() is decorated
            // @stub_and_esp32_function_only - a client-side guard that
            // raises before ever sending anything over the wire unless
            // IS_STUB or the chip is ESP32 or one of its descendants
            // (S2/S3/C3/...). Every chip except ESP8266 inherits from
            // ESP32ROM in the reference implementation, so in practice
            // this capability is universal except on ESP8266's ROM - which
            // matches this app's own [ChipType.supportsCompressedWrite]
            // exclusion exactly, for the same underlying reason (ESP8266's
            // ROM is uniquely limited; every other chip's ROM is not).
            // Attempting flash_md5 there anyway doesn't fail gracefully -
            // the ROM has no handler for it at all - so this is skipped
            // client-side, same as the reference does, rather than sent
            // and left to fail in some ROM-specific way.
            if (ld.chip == ChipType.ESP8266 && !stubLoader.isRunning) {
                callback.onLog(
                    LogLevel.INFO,
                    "${image.label}: ESP8266's ROM has no flash_md5 command at all (only the stub adds it) - " +
                        "skipping the automated checksum check for this file. The write itself already succeeded."
                )
                continue
            }

            if (stubLoader.isRunning) {
                // Same reasoning as the drain barrier in transferToFlash: a
                // stub write's ack doesn't mean the write physically landed
                // yet. flash_md5 asks the device to hash the region *right
                // now* - if the write from writeImages() (or an earlier
                // session) hasn't actually finished landing, this would
                // hash stale or partial flash content and fail for a reason
                // that has nothing to do with the data actually being
                // wrong. A throwaway register read forces the device
                // through its single command-at-a-time processing loop
                // first, same as esptool's own read_reg() barrier here.
                runCatching { ld.readReg(EspCommand.CHIP_DETECT_MAGIC_REG_ADDR) }
            }
            callback.onLog(LogLevel.COMMAND, "flash_md5 offset=0x%X size=0x%X".format(image.offset, data.size))
            val remoteMd5 = ld.flashMd5(image.offset, data.size.toLong(), isStub = stubLoader.isRunning)
            if (!localMd5.equals(remoteMd5, ignoreCase = true)) {
                throw FlasherException.ChecksumMismatch(localMd5, remoteMd5)
            }
            callback.onLog(LogLevel.SUCCESS, "${image.label} verified OK (MD5 $localMd5)")
        }
    }

    // -----------------------------------------------------------------
    // Read / dump
    // -----------------------------------------------------------------

    suspend fun readFlashToFile(offset: Long, length: Long, destUri: Uri, readBaudRate: Int, callback: FlashCallback) {
        val data = readFlashBytes(offset, length, readBaudRate, "Reading flash", callback)
        context.contentResolver.openOutputStream(destUri)?.use { it.write(data) }
            ?: throw FlasherException.Generic("Could not open the destination file for writing.")
        callback.onProgress(ProgressState.IDLE)
        callback.onLog(LogLevel.SUCCESS, "Read ${data.size} bytes to file.")
    }

    /**
     * Reads a region off the device and returns it directly rather than
     * writing to a [Uri] - for callers that need to parse the bytes
     * themselves (e.g. [io.espflasher.app.spiffs.SpiffsImageExtractor]
     * reading a live SPIFFS partition) rather than just save a dump.
     *
     * Prefers the stub's [EspRomLoader.readFlashStub] whenever a stub is
     * running - it's the only read path at all on every chip except
     * classic ESP32, and even there it's far faster (one flash sector,
     * 0x1000 bytes, per round trip instead of ROM-only's 64 bytes). Falls
     * back to [EspRomLoader.readFlashRomOnly] only when no stub is active,
     * which still means "ESP32 only" - see that function's own doc comment.
     */
    suspend fun readFlashBytes(offset: Long, length: Long, readBaudRate: Int, label: String, callback: FlashCallback): ByteArray {
        val (ld, chip) = ensureReady(callback)
        val p = port ?: throw FlasherException.UsbDisconnected()
        if (!stubLoader.isRunning && !ld.supportsRomOnlyRead(chip)) {
            throw FlasherException.Generic(
                "Reading flash on ${chip.displayName} needs a stub loader - turn on \"use stub loader\" under " +
                    "Advanced and run Detect again, then retry. ROM-only reading (no stub needed) only works on classic ESP32."
            )
        }
        return withFastBaud(ld, p, chip, readBaudRate, callback) {
            val tracker = ProgressTracker(length, label)
            val progress: (Long, Long) -> Unit = { done, _ -> callback.onProgress(tracker.update(done, offset + done)) }
            val data = if (stubLoader.isRunning) {
                callback.onLog(LogLevel.COMMAND, "read_flash (stub) offset=0x%X size=0x%X".format(offset, length))
                // Unlike a single flash_data write, one bad sector partway
                // through can't be individually retried in place - the ack
                // protocol is a running cumulative total, so a desync means
                // starting the whole read over from offset 0 is the only way
                // back to a known-good state. Bounded at 2 total attempts.
                var lastError: FlasherException? = null
                var result: ByteArray? = null
                for (attempt in 1..2) {
                    try {
                        result = ld.readFlashStub(offset, length, progress)
                        break
                    } catch (e: FlasherException) {
                        lastError = e
                        if (attempt < 2) {
                            callback.onLog(LogLevel.WARNING, "Flash read failed (${e.message}) - retrying from the start...")
                            // A failed read can leave the stub mid-stream,
                            // still expecting acks for the read that never
                            // finished - a fresh READ_FLASH trigger sent
                            // straight into that state has nothing to
                            // respond to it with. Nudge the connection back
                            // to a known state first; if even that fails,
                            // let the original error stand rather than
                            // mask it with a sync failure.
                            runCatching { ld.sync() }
                        }
                    }
                }
                result ?: throw lastError!!
            } else {
                callback.onLog(LogLevel.COMMAND, "read_flash (ROM) offset=0x%X size=0x%X".format(offset, length))
                ld.readFlashRomOnly(offset, length, progress)
            }
            callback.onProgress(ProgressState.IDLE)
            data
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private suspend fun ensureReady(callback: FlashCallback): Pair<EspRomLoader, ChipType> {
        val ld = requireLoader()
        // A plain ROM sync from earlier in the session is NOT enough once
        // "use stub loader" has been turned on but no stub has actually
        // been uploaded yet - without this check, checking the box and then
        // going straight to Erase/Flash (instead of pressing Detect again
        // first) would silently keep using the old ROM-only session state,
        // and every stub-only fast path below would quietly never trigger.
        val staleForStub = useStubLoader && !stubLoader.isRunning
        val chip = if (inBootloader && detectedChip != null && !staleForStub) {
            detectedChip!!
        } else {
            detectDevice(callback).chip
        }
        return ld to chip
    }

    /**
     * Brackets a data-heavy operation (upload or read) with a temporary
     * switch to [targetBaud] - the rate the person picked in the UI for
     * this - reverting to whatever baud the connection started at once
     * [block] finishes, whether it succeeded, failed, or was cancelled.
     * Detect and Erase deliberately never call this: they always run at
     * whatever baud the person connected with (115200 by default, matching
     * esptool's own default), and only the actual upload/read pays the cost
     * of switching up and back down, exactly at the point data starts
     * moving - not before.
     *
     * The revert is careful to never depend on the chip still being there:
     * if it doesn't answer the revert command (e.g. it was reset to run the
     * app as part of this same operation, or the target rate never really
     * worked in the first place), only the local port's baud is fixed back
     * up rather than raising an error. Either way, a fresh sync is always
     * attempted afterward (logged, not fatal) so the console clearly shows
     * whether the connection is still good for whatever comes next.
     */
    private suspend fun <T> withFastBaud(
        ld: EspRomLoader,
        p: io.espflasher.app.usb.UsbSerialPort,
        chip: ChipType,
        targetBaud: Int,
        callback: FlashCallback,
        block: suspend () -> T
    ): T {
        val originalBaud = p.config.baudRate
        var switchedUp = false
        if (originalBaud != targetBaud && !chip.supportsBaudChange && !stubLoader.isRunning) {
            callback.onLog(
                LogLevel.INFO,
                "${chip.displayName}'s ROM bootloader can't change baud rate either - staying at $originalBaud baud."
            )
        } else if (originalBaud != targetBaud) {
            callback.onLog(LogLevel.INFO, "Switching to $targetBaud baud for upload...")
            val switched = runCatching {
                ld.changeBaudRate(targetBaud, oldBaud = originalBaud, isStub = stubLoader.isRunning)
                ld.sync(maxAttempts = 3)
            }.getOrDefault(false)
            if (switched) {
                switchedUp = true
                callback.onLog(LogLevel.SUCCESS, "Now running at $targetBaud baud")
            } else {
                callback.onLog(LogLevel.WARNING, "No response at $targetBaud baud - staying at $originalBaud")
                runCatching { p.setBaudRate(originalBaud) }
                if (!ld.sync(maxAttempts = 3)) throw FlasherException.SyncFailed(3)
            }
        }
        try {
            return block()
        } finally {
            if (switchedUp) {
                val reverted = runCatching {
                    ld.changeBaudRate(originalBaud, oldBaud = targetBaud, isStub = stubLoader.isRunning)
                    true
                }.getOrDefault(false)
                if (!reverted) {
                    runCatching { p.setBaudRate(originalBaud) }
                }
                callback.onLog(LogLevel.INFO, "Back to $originalBaud baud")
            }
            // Always confirm the connection afterward, success or failure -
            // best effort, never masks whatever exception is already in flight.
            val stillSynced = runCatching { ld.sync(maxAttempts = 2) }.getOrDefault(false)
            if (stillSynced) {
                callback.onLog(LogLevel.SUCCESS, "<- Sync OK")
            } else {
                callback.onLog(LogLevel.WARNING, "No sync response after baud revert (chip may have reset or is no longer in the bootloader).")
            }
        }
    }

    private fun requireLoader(): EspRomLoader = loader ?: throw FlasherException.UsbDisconnected()

    /**
     * [chip] gates [EspImageChecksum.fix]: ESP8266 images use a shorter
     * header with no appended hash at all, so that fixup must only run for
     * ESP32-series chips - see that object's own doc comment for why it's
     * needed at all whenever credentials get patched into a file.
     */
    private fun readAllBytes(uri: Uri, chip: ChipType, callback: FlashCallback): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FlasherException.Generic("Could not open file $uri")
        if (credentials.isEmpty) return raw
        var anyPatched = false
        val patched = BinaryCredentialPatcher.applyAll(raw, credentials) { placeholder, occurrences ->
            if (occurrences > 0) {
                anyPatched = true
                callback.onLog(LogLevel.INFO, "Patched ${placeholder.label}: $occurrences occurrence(s) in this file.")
            }
        }
        if (!anyPatched || !chip.isEsp32Series) return patched
        val fixed = EspImageChecksum.fix(patched)
        if (fixed === patched) {
            callback.onLog(LogLevel.WARNING, "Patched credentials but this doesn't look like a flashable ESP app image - checksum/hash left as-is.")
        } else {
            callback.onLog(LogLevel.INFO, "Recomputed image checksum/hash after patching credentials.")
        }
        return fixed
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun chipFeatures(chip: ChipType): List<String> = when (chip) {
        ChipType.ESP8266 -> listOf("Wi-Fi", "Single-core Xtensa L106")
        ChipType.ESP32 -> listOf("Wi-Fi", "Bluetooth Classic", "BLE", "Dual-core Xtensa LX6")
        ChipType.ESP32_S2 -> listOf("Wi-Fi", "USB OTG", "Single-core Xtensa LX7")
        ChipType.ESP32_S3 -> listOf("Wi-Fi", "BLE", "USB OTG", "Dual-core Xtensa LX7", "AI vector instructions")
        ChipType.ESP32_C3 -> listOf("Wi-Fi", "BLE", "Single-core RISC-V")
        ChipType.ESP32_C6 -> listOf("Wi-Fi 6", "BLE", "802.15.4 (Thread/Zigbee)", "Single-core RISC-V")
        ChipType.ESP32_H2 -> listOf("BLE", "802.15.4 (Thread/Zigbee)", "Single-core RISC-V")
        ChipType.ESP32_C2 -> listOf("Wi-Fi", "BLE", "Single-core RISC-V", "Cost-reduced C3 variant")
        ChipType.ESP32_C5 -> listOf("Wi-Fi 6 (2.4/5 GHz)", "BLE", "802.15.4", "Single-core RISC-V")
        ChipType.ESP32_C61 -> listOf("Wi-Fi 6", "BLE", "802.15.4", "Single-core RISC-V")
        ChipType.ESP32_H21 -> listOf("BLE", "802.15.4 (Thread/Zigbee)", "Single-core RISC-V", "Cost-reduced H2 variant")
        ChipType.ESP32_H4 -> listOf("BLE", "802.15.4 (Thread/Zigbee)", "RISC-V")
        ChipType.ESP32_P4 -> listOf("High-performance MCU (no radio)", "Dual-core RISC-V", "USB OTG/HS")
        ChipType.ESP32_S31 -> listOf("Wi-Fi", "BLE", "USB OTG", "RISC-V")
        ChipType.ESP32_E22 -> listOf("BLE / 802.15.4", "RISC-V")
    }
}
