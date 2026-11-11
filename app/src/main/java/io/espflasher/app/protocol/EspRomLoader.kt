package io.espflasher.app.protocol

import io.espflasher.app.util.BinaryUtils.concat
import io.espflasher.app.util.BinaryUtils.hex
import io.espflasher.app.util.BinaryUtils.macString
import io.espflasher.app.util.BinaryUtils.readU32LE
import io.espflasher.app.util.BinaryUtils.u16le
import io.espflasher.app.util.BinaryUtils.u32le
import kotlinx.coroutines.delay

/** SPI flash JEDEC ID (RDID / 0x9F) decode result. */
data class FlashId(val manufacturer: Int, val memoryType: Int, val capacityByte: Int) {
    val sizeBytes: Long? get() = FlashLayout.capacityByteToSize(capacityByte)
}

/** Parsed GET_SECURITY_INFO response. */
data class SecurityInfo(
    val flags: Long,
    val flashCryptCnt: Int,
    val chipId: Int?,
    val apiVersion: Int?
) {
    val secureBootEnabled: Boolean get() = (flags and (1L shl 0)) != 0L
    val secureDownloadEnabled: Boolean get() = (flags and (1L shl 2)) != 0L
    val jtagSoftDisabled: Boolean get() = (flags and (1L shl 6)) != 0L
    val jtagHardDisabled: Boolean get() = (flags and (1L shl 7)) != 0L
    val usbDisabled: Boolean get() = (flags and (1L shl 8)) != 0L
    val flashEncryptionEnabled: Boolean get() = flashCryptCnt != 0
}

/**
 * Talks the Espressif serial ROM-loader protocol over any [SerialTransport].
 *
 * This is an original, from-scratch implementation written directly against
 * the publicly documented wire protocol (SLIP framing + the command set in
 * [EspCommand]) - it does not embed, shell out to, or otherwise invoke
 * esptool.py or any other third-party flashing tool.
 *
 * Every chip family (ESP8266 through ESP32-H2) is driven purely through the
 * ROM bootloader that ships permanently burned into the silicon; no stub
 * loader is uploaded by default (see [StubLoader] for why, and what that
 * trades off). This covers sync, chip/flash identification,
 * full-chip erase, flashing, and MD5 verification on every supported chip,
 * plus ROM-native flash reading on classic ESP32 (the only chip whose ROM
 * exposes a flash-read primitive at all - see [readFlashRomOnly]).
 */
class EspRomLoader(private val transport: SerialTransport) {

    var chip: ChipType? = null
        private set

    // Bytes already pulled out of the transport by a previous readFrame()
    // call but not yet fed to a SlipCodec.Reader - see readFrame()'s doc
    // comment for why these can exist and why losing them is a real bug,
    // not just a theoretical one.
    private var pendingBytes: ByteArray = ByteArray(0)
    private var pendingPos: Int = 0

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 3000L
        private const val SYNC_TIMEOUT_MS = 100L
        private const val ERASE_TIMEOUT_PER_MB_MS = 30000L
        private const val MD5_TIMEOUT_PER_MB_MS = 8000L

        /**
         * Chunk size for [readFrame]'s bulk [SerialTransport.readBytes] calls.
         * Matches [io.espflasher.app.usb.AbstractUsbSerialPort.READ_BUFFER_SIZE]
         * so a single call can drain everything the transport already has
         * buffered - no protocol reason for this exact number, just "large
         * enough that a stub's 4096-byte flash-read sector needs only a
         * couple of calls instead of thousands of one-byte ones."
         */
        private const val READ_CHUNK_SIZE = 4096

        /**
         * Total attempts (including the first) for a single FLASH_DATA /
         * FLASH_DEFL_DATA block before giving up on it. Matches esptool's
         * `WRITE_BLOCK_ATTEMPTS` (default 3) and Espressif's own
         * `esp-serial-flasher` C library's `SERIAL_FLASHER_WRITE_BLOCK_RETRIES`
         * (also 3) - both official Espressif tools retry a single failed
         * block in place (same sequence number) rather than aborting the
         * whole transfer, because a lone checksum/timeout glitch on one
         * block over a raw USB-UART link is a real, fairly common failure
         * mode that has nothing to do with the rest of the transfer.
         */
        private const val WRITE_BLOCK_ATTEMPTS = 3

        /**
         * Matches esptool's timeout_per_mb(): scales linearly with size using
         * decimal megabytes (size / 1,000,000), floored at the default 3s
         * command timeout. Erasing is genuinely slow on some cheap SPI NOR
         * parts, so this needs to be generous - esptool uses 30s/MB for erase,
         * not a smaller "should be enough" guess.
         */
        private fun timeoutForSize(msPerMb: Long, sizeBytes: Long): Long {
            val scaled = (msPerMb * sizeBytes) / 1_000_000L
            return scaled.coerceAtLeast(DEFAULT_TIMEOUT_MS)
        }

        /**
         * Whether a failed block write is worth retrying in place. Transient
         * protocol-level failures (a garbled checksum, a slow reply) are -
         * that's the whole point of [WRITE_BLOCK_ATTEMPTS]. Cancellation and
         * connection-level failures are not: retrying after the user hit
         * Stop would make cancellation feel unresponsive (see the class doc
         * on Stop), and retrying after the transport itself is gone (USB
         * unplugged, permission revoked) can't possibly succeed - both
         * should propagate immediately instead of burning two more attempts.
         */
        private fun isRetryable(e: FlasherException): Boolean = when (e) {
            is FlasherException.OperationCancelled,
            is FlasherException.UsbDisconnected,
            is FlasherException.PermissionDenied -> false
            else -> true
        }
    }

    // ---------------------------------------------------------------------
    // Low-level packet exchange
    // ---------------------------------------------------------------------

    private fun checksum(data: ByteArray): Int {
        var state = EspCommand.CHECKSUM_MAGIC
        for (b in data) state = state xor (b.toInt() and 0xFF)
        return state and 0xFF
    }

    /**
     * Reads and reassembles one SLIP frame. Pulls bytes via
     * [SerialTransport.readBytes] in chunks rather than
     * [SerialTransport.readByteOrNull] one call per byte - see that
     * method's doc comment for why the difference matters on real hardware:
     * this was the actual cause of "Corrupt data during flash read:
     * expected a 4096-byte sector but got N bytes" errors during
     * stub-based reads, where the per-byte version couldn't drain the USB
     * endpoint fast enough during a multi-kilobyte burst.
     *
     * Reading in chunks introduces a problem the per-byte version never
     * had, though: a single [SerialTransport.readBytes] call can return
     * bytes belonging to MORE than one frame at once (e.g. a ROM's MEM_END
     * ack immediately followed by a stub's unprompted "OHAI" greeting,
     * both already sitting in the endpoint's receive queue by the time we
     * poll it). The first version of this fix fed the whole chunk to a
     * fresh [SlipCodec.Reader] and returned as soon as ONE frame completed,
     * silently discarding whatever came after it in that same chunk - which
     * in practice meant [memFinish]'s "drain the MEM_END ack" read was
     * quietly eating the stub's greeting along with it, so [readRawFrame]'s
     * subsequent wait for "OHAI" saw nothing and timed out every time.
     * [pendingBytes]/[pendingPos] carry any such leftover across to the
     * NEXT call to this function instead of dropping it.
     */
    private suspend fun readFrame(timeoutMs: Long): ByteArray? {
        val reader = SlipCodec.Reader()
        val deadline = System.currentTimeMillis() + timeoutMs
        val chunk = ByteArray(READ_CHUNK_SIZE)
        while (true) {
            if (pendingPos < pendingBytes.size) {
                val b = pendingBytes[pendingPos].toInt() and 0xFF
                pendingPos++
                val frame = reader.feed(b)
                if (frame != null) return frame
                continue
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val n = transport.readBytes(chunk, remaining)
            if (n <= 0) return null
            for (i in 0 until n) {
                val frame = reader.feed(chunk[i].toInt() and 0xFF)
                if (frame != null) {
                    pendingBytes = if (i + 1 < n) chunk.copyOfRange(i + 1, n) else ByteArray(0)
                    pendingPos = 0
                    return frame
                }
            }
        }
    }

    /**
     * Discards buffered input at both layers: the transport's own receive
     * buffer, and this frame reader's [pendingBytes] carry-over from a
     * previous over-read (see [readFrame]'s doc comment). Forgetting the
     * second one here would mean a stale frame fragment from before a
     * resync/reconnect could get fed into a completely unrelated later
     * read.
     */
    private suspend fun purgeReceiveState() {
        transport.purgeInput()
        pendingBytes = ByteArray(0)
        pendingPos = 0
    }

    /**
     * Send a command and wait for its response, following the same retry
     * behaviour as the reference protocol: some ROMs (notably ESP8266) can
     * emit stray extra SYNC replies, so we keep reading frames until one
     * matches the opcode we sent or we give up.
     */
    private suspend fun command(
        op: Int?,
        data: ByteArray = ByteArray(0),
        chk: Int = 0,
        waitResponse: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Pair<Long, ByteArray> {
        if (op != null) {
            val header = concat(
                byteArrayOf(0x00, op.toByte()),
                u16le(data.size),
                u32le(chk.toLong())
            )
            transport.write(SlipCodec.encode(concat(header, data)))
        }
        if (!waitResponse) return 0L to ByteArray(0)

        val opName = opDisplayName(op)
        repeat(100) {
            val frame = readFrame(timeoutMs) ?: throw FlasherException.FlashTimeout(opName)
            if (frame.size < 8) return@repeat
            val respByte = frame[0].toInt() and 0xFF
            val opRet = frame[1].toInt() and 0xFF
            val value = readU32LE(frame, 4)
            val respData = frame.copyOfRange(8, frame.size)
            if (respByte != 1) return@repeat
            if (op == null || opRet == op) {
                return value to respData
            }
        }
        throw FlasherException.FlashTimeout(opName)
    }

    /** Runs [command] and validates the two trailing status bytes the ROM appends. */
    private suspend fun exec(
        opName: String,
        op: Int,
        data: ByteArray = ByteArray(0),
        chk: Int = 0,
        respDataLen: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Pair<Long, ByteArray> {
        val (value, respData) = command(op, data, chk, timeoutMs = timeoutMs)
        if (respData.size < respDataLen + 2) {
            val status = if (respData.isNotEmpty()) respData[0].toInt() and 0xFF else 0
            if (status != 0) {
                val reason = if (respData.size > 1) respData[1].toInt() and 0xFF else 0
                throw FlasherException.RomError(opName, status, reason)
            }
            throw FlasherException.FlashTimeout(opName)
        }
        val status = respData[respDataLen].toInt() and 0xFF
        if (status != 0) {
            val reason = respData[respDataLen + 1].toInt() and 0xFF
            throw FlasherException.RomError(opName, status, reason)
        }
        return value to if (respDataLen > 0) respData.copyOfRange(0, respDataLen) else ByteArray(0)
    }

    private fun opDisplayName(op: Int?): String = when (op) {
        EspCommand.SYNC -> "sync"
        EspCommand.FLASH_BEGIN -> "flash begin"
        EspCommand.FLASH_DATA -> "flash data"
        EspCommand.FLASH_END -> "flash end"
        EspCommand.FLASH_DEFL_BEGIN -> "compressed flash begin"
        EspCommand.FLASH_DEFL_DATA -> "compressed flash data"
        EspCommand.FLASH_DEFL_END -> "compressed flash end"
        EspCommand.MEM_BEGIN -> "mem begin"
        EspCommand.MEM_DATA -> "mem data"
        EspCommand.MEM_END -> "mem end"
        EspCommand.READ_REG -> "read register"
        EspCommand.WRITE_REG -> "write register"
        EspCommand.SPI_ATTACH -> "SPI attach"
        EspCommand.SPI_FLASH_MD5 -> "flash MD5"
        EspCommand.CHANGE_BAUDRATE -> "change baud rate"
        EspCommand.GET_SECURITY_INFO -> "get security info"
        EspCommand.READ_FLASH_SLOW -> "read flash"
        else -> "command 0x%02X".format(op ?: -1)
    }

    // ---------------------------------------------------------------------
    // Sync / handshake
    // ---------------------------------------------------------------------

    /** Repeatedly issues SYNC until the ROM answers or [maxAttempts] is exhausted. */
    suspend fun sync(maxAttempts: Int = 7): Boolean {
        repeat(maxAttempts) { attempt ->
            purgeReceiveState()
            try {
                command(EspCommand.SYNC, EspCommand.SYNC_PAYLOAD, timeoutMs = SYNC_TIMEOUT_MS)
                // Drain the extra sync replies the ROM sends after the first one.
                repeat(7) {
                    try {
                        command(null, waitResponse = true, timeoutMs = 50)
                    } catch (_: FlasherException) {
                        return@repeat
                    }
                }
                return true
            } catch (_: FlasherException) {
                // try again
            }
            delay(50L * (attempt + 1))
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Register read/write (also exposed for the Advanced panel)
    // ---------------------------------------------------------------------

    suspend fun readReg(addr: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Long {
        val (value, _) = exec("read register", EspCommand.READ_REG, u32le(addr), timeoutMs = timeoutMs)
        return value
    }

    suspend fun writeReg(addr: Long, value: Long, mask: Long = 0xFFFFFFFFL, delayUs: Int = 0) {
        val data = concat(u32le(addr), u32le(value), u32le(mask), u32le(delayUs.toLong()))
        exec("write register", EspCommand.WRITE_REG, data)
    }

    // ---------------------------------------------------------------------
    // Chip / flash identification
    // ---------------------------------------------------------------------

    /** Reads the chip-detect magic register, then falls back to GET_SECURITY_INFO's chip_id. */
    suspend fun detectChip(): ChipType {
        val magic = readReg(EspCommand.CHIP_DETECT_MAGIC_REG_ADDR)
        ChipType.byMagicValue(magic)?.let {
            chip = it
            return it
        }
        // Newer chips (S3/C3/C6/H2...) don't expose a magic value; identify via chip_id instead.
        val info = getSecurityInfoOrNull(EspCommand.GET_SECURITY_INFO)
        val id = info?.chipId
        if (id != null) {
            ChipType.byImageChipId(id)?.let {
                chip = it
                return it
            }
        }
        throw FlasherException.UnsupportedChip("magic=0x%08X".format(magic))
    }

    suspend fun getSecurityInfo(): SecurityInfo? {
        val c = chip
        if (c != null && !c.supportsSecurityInfo) return null
        return getSecurityInfoOrNull(EspCommand.GET_SECURITY_INFO)
    }

    private suspend fun getSecurityInfoOrNull(op: Int): SecurityInfo? {
        return try {
            // Try the newer 20-byte reply (flags:4, flash_crypt_cnt:1, key_purposes:7, chip_id:4, api_version:4)
            val (_, data) = exec("get security info", op, respDataLen = 20)
            val flags = readU32LE(data, 0)
            val flashCryptCnt = data[4].toInt() and 0xFF
            val chipId = readU32LE(data, 12).toInt()
            val apiVersion = readU32LE(data, 16).toInt()
            SecurityInfo(flags, flashCryptCnt, chipId, apiVersion)
        } catch (e: FlasherException) {
            try {
                // ESP32-S2 only returns the 12-byte short form, with no chip_id.
                val (_, data) = exec("get security info", op, respDataLen = 12)
                val flags = readU32LE(data, 0)
                val flashCryptCnt = data[4].toInt() and 0xFF
                SecurityInfo(flags, flashCryptCnt, null, null)
            } catch (e2: FlasherException) {
                null
            }
        }
    }

    /**
     * Reads the factory-programmed base MAC address from efuse.
     *
     * ESP8266 and classic ESP32 scatter the MAC bits across several efuse
     * words in a legacy layout; every later chip stores it as two contiguous
     * 32-bit words at [ChipType.efuseMacRegAddr]. Both paths are implemented.
     */
    suspend fun readMac(c: ChipType): String {
        return when (c) {
            ChipType.ESP32 -> readMacEsp32()
            ChipType.ESP8266 -> readMacEsp8266()
            else -> {
                val word0 = readReg(c.efuseMacRegAddr)
                val word1 = readReg(c.efuseMacRegAddr + 4)
                val bytes = ByteArray(6)
                bytes[0] = ((word1 shr 8) and 0xFF).toByte()
                bytes[1] = (word1 and 0xFF).toByte()
                bytes[2] = ((word0 shr 24) and 0xFF).toByte()
                bytes[3] = ((word0 shr 16) and 0xFF).toByte()
                bytes[4] = ((word0 shr 8) and 0xFF).toByte()
                bytes[5] = (word0 and 0xFF).toByte()
                macString(bytes)
            }
        }
    }

    private suspend fun readMacEsp32(): String {
        // EFUSE_RD_REG_BASE + 0x04*n legacy blocks; MAC lives split across
        // BLK0 words 1 and 2 on original ESP32 silicon.
        val base = 0x3FF5A000L
        val word1 = readReg(base + 4)
        val word2 = readReg(base + 8)
        val bytes = ByteArray(6)
        bytes[0] = ((word2 shr 8) and 0xFF).toByte()
        bytes[1] = (word2 and 0xFF).toByte()
        bytes[2] = ((word1 shr 24) and 0xFF).toByte()
        bytes[3] = ((word1 shr 16) and 0xFF).toByte()
        bytes[4] = ((word1 shr 8) and 0xFF).toByte()
        bytes[5] = (word1 and 0xFF).toByte()
        return macString(bytes)
    }

    private suspend fun readMacEsp8266(): String {
        val word1 = readReg(0x3FF00050L)
        val word2 = readReg(0x3FF00054L)
        val word3 = readReg(0x3FF0005CL)
        val bytes = ByteArray(6)
        if ((word3 shr 16) and 0xFF == 0L) {
            bytes[0] = 0x18; bytes[1] = 0xFE.toByte(); bytes[2] = 0x34
        } else if ((word3 shr 16) and 0xFF == 1L) {
            bytes[0] = 0xAC.toByte(); bytes[1] = 0xD0.toByte(); bytes[2] = 0x74
        } else {
            bytes[0] = ((word2 shr 16) and 0xFF).toByte()
            bytes[1] = ((word2 shr 8) and 0xFF).toByte()
            bytes[2] = (word2 and 0xFF).toByte()
        }
        bytes[3] = ((word1 shr 8) and 0xFF).toByte()
        bytes[4] = (word1 and 0xFF).toByte()
        bytes[5] = ((word3 shr 24) and 0xFF).toByte()
        return macString(bytes)
    }

    // ---------------------------------------------------------------------
    // SPI flash controller access (generic USR-command interface) - this is
    // what lets us read the flash JEDEC ID / size with no stub loaded, by
    // driving the same on-chip SPI peripheral esptool does, purely through
    // the generic READ_REG/WRITE_REG opcodes every ROM already supports.
    // ---------------------------------------------------------------------

    suspend fun spiAttach() {
        exec("SPI attach", EspCommand.SPI_ATTACH, u32le(0))
    }

    /**
     * Tells the ROM bootloader the real flash chip size (plus the standard
     * block/sector/page geometry) so it can validate erase/write bounds.
     * Without this, the ROM has no idea how big the flash actually is and
     * can reject perfectly valid requests - most notably a whole-chip erase -
     * with a generic "message is ok, but the running result is wrong" error.
     * esptool calls this once per session, right after flash-size detection
     * and before any erase/write; this mirrors that.
     */
    suspend fun setSpiFlashParameters(totalSizeBytes: Long) {
        val flashId = 0L
        val blockSize = 64L * 1024L
        val sectorSize = FlashLayout.SECTOR_SIZE.toLong()
        val pageSize = FlashLayout.PAGE_SIZE.toLong()
        val statusMask = 0xFFFFL
        val params = concat(
            u32le(flashId), u32le(totalSizeBytes), u32le(blockSize), u32le(sectorSize), u32le(pageSize), u32le(statusMask)
        )
        exec("set SPI flash parameters", EspCommand.SPI_SET_PARAMS, params)
    }

    suspend fun runSpiFlashCommand(
        c: ChipType,
        spiCommand: Int,
        data: ByteArray = ByteArray(0),
        readBits: Int = 0,
        addr: Long? = null,
        addrLen: Int = 0,
        dummyLen: Int = 0
    ): Long {
        require(readBits <= 32) { "Reading more than 32 bits is unsupported" }
        require(data.size <= 64) { "Writing more than 64 bytes is unsupported" }

        val base = c.spiRegBase
        val cmdReg = base + 0x00
        val addrReg = base + 0x04
        val usrReg = base + c.spiUsrOffset
        val usr1Reg = base + c.spiUsr1Offset
        val usr2Reg = base + c.spiUsr2Offset
        val w0Reg = base + c.spiW0Offset

        val usrCommand = 1L shl 31
        val usrAddr = 1L shl 30
        val usrDummy = 1L shl 29
        val usrMiso = 1L shl 28
        val usrMosi = 1L shl 27
        val cmdUsr = 1L shl 18
        val usr2CmdLenShift = 28
        val usrAddrLenShift = 26

        val dataBits = data.size * 8

        if (c.spiMosiDlenOffset != null && c.spiMisoDlenOffset != null) {
            if (dataBits > 0) writeReg(base + c.spiMosiDlenOffset, (dataBits - 1).toLong())
            if (readBits > 0) writeReg(base + c.spiMisoDlenOffset, (readBits - 1).toLong())
            var flags = 0L
            if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
            if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl usrAddrLenShift)
            if (flags != 0L) writeReg(usr1Reg, flags)
        } else {
            // Legacy ESP8266-style combined length register.
            val mosiShift = 17
            val misoShift = 8
            val mosiMask = if (dataBits == 0) 0L else (dataBits - 1).toLong()
            val misoMask = if (readBits == 0) 0L else (readBits - 1).toLong()
            var flags = (misoMask shl misoShift) or (mosiMask shl mosiShift)
            if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
            if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl usrAddrLenShift)
            writeReg(usr1Reg, flags)
        }

        val oldUsr = readReg(usrReg)
        val oldUsr2 = readReg(usr2Reg)

        var flags = usrCommand
        if (readBits > 0) flags = flags or usrMiso
        if (dataBits > 0) flags = flags or usrMosi
        if (addrLen > 0) flags = flags or usrAddr
        if (dummyLen > 0) flags = flags or usrDummy
        writeReg(usrReg, flags)
        writeReg(usr2Reg, (7L shl usr2CmdLenShift) or spiCommand.toLong())

        if (addrLen > 0 && addr != null) {
            val addrValue = if (c.spiAddrRegMsb) addr shl (32 - addrLen) else addr
            writeReg(addrReg, addrValue)
        }

        if (dataBits == 0) {
            writeReg(w0Reg, 0)
        } else {
            val padded = data.copyOf(((data.size + 3) / 4) * 4)
            var reg = w0Reg
            var i = 0
            while (i < padded.size) {
                val word = readU32LE(padded, i)
                writeReg(reg, word)
                reg += 4
                i += 4
            }
        }

        writeReg(cmdReg, cmdUsr)

        var done = false
        repeat(10) {
            if ((readReg(cmdReg) and cmdUsr) == 0L) {
                done = true
                return@repeat
            }
        }
        if (!done) throw FlasherException.FlashTimeout("SPI flash command")

        val status = readReg(w0Reg)
        writeReg(usrReg, oldUsr)
        writeReg(usr2Reg, oldUsr2)
        return status
    }

    suspend fun readFlashId(c: ChipType): FlashId {
        val result = runSpiFlashCommand(c, spiCommand = 0x9F, readBits = 24)
        val manufacturer = (result and 0xFF).toInt()
        val memType = ((result shr 8) and 0xFF).toInt()
        val capacity = ((result shr 16) and 0xFF).toInt()
        return FlashId(manufacturer, memType, capacity)
    }

    // ---------------------------------------------------------------------
    // Baud rate
    // ---------------------------------------------------------------------

    /**
     * @param oldBaud the baud rate in effect *right now*, before this
     *   switch - Python's reference passes `self._port.baudrate` here
     *   whenever a stub is running (and 0 for ROM-only, which doesn't use
     *   this field at all). This was previously hardcoded to 0
     *   unconditionally, which only happened to be harmless for ROM
     *   sessions; for a stub session it's a real, silent divergence from
     *   what the stub was actually built expecting in this field.
     * @param isStub whether a stub is currently running - [EspRomLoader]
     *   doesn't track this itself, matching how [flashMd5] and
     *   [flashFinish] already take it from the caller rather than guessing.
     */
    suspend fun changeBaudRate(newBaud: Int, oldBaud: Int, isStub: Boolean) {
        val secondArg = if (isStub) oldBaud else 0
        exec("change baud rate", EspCommand.CHANGE_BAUDRATE, concat(u32le(newBaud.toLong()), u32le(secondArg.toLong())))
        transport.setBaudRate(newBaud)
        delay(50)
        purgeReceiveState()
    }

    // ---------------------------------------------------------------------
    // RAM (mem_*) upload - used only if/when a stub loader is enabled; see
    // io.espflasher.app.protocol.StubLoader for the extension point.
    // ---------------------------------------------------------------------

    suspend fun memBegin(size: Long, blocks: Int, blockSize: Int, offset: Long) {
        exec("mem begin", EspCommand.MEM_BEGIN, concat(u32le(size), u32le(blocks.toLong()), u32le(blockSize.toLong()), u32le(offset)))
    }

    suspend fun memBlock(data: ByteArray, seq: Int) {
        val header = concat(u32le(data.size.toLong()), u32le(seq.toLong()), u32le(0), u32le(0))
        exec("mem data", EspCommand.MEM_DATA, concat(header, data), chk = checksum(data))
    }

    /**
     * Sends MEM_END (with the given entry point, if non-zero, telling the
     * ROM to jump there once done). The ROM normally answers this with a
     * completely ordinary response - same shape as any other command - but
     * jumping to freshly uploaded code can occasionally disrupt the reply
     * before it's fully sent, so this reads-and-discards rather than
     * treating a timeout as a real failure: the response (if it arrives)
     * carries no information the caller needs, but it MUST be drained here
     * rather than left sitting in the receive buffer - otherwise the very
     * next read anyone does (e.g. a [StubLoader] watching for the stub's
     * own greeting once it starts) picks up this leftover frame instead of
     * whatever actually comes next, and misreads a perfectly normal MEM_END
     * ack as "nothing happened."
     */
    suspend fun memFinish(entryPoint: Long = 0) {
        val data = concat(u32le(if (entryPoint == 0L) 1 else 0), u32le(entryPoint))
        runCatching { command(EspCommand.MEM_END, data, waitResponse = true, timeoutMs = 1000) }
    }

    /**
     * Reads one raw SLIP frame with no response-header interpretation at all -
     * unlike every other read in this file, which goes through [command] and
     * expects the normal 8-byte-header response shape. Exists for exactly one
     * purpose: after [memFinish] jumps to freshly uploaded code, that code's
     * very first action (if it's Espressif's flasher stub) is to send the
     * literal 4 ASCII bytes "OHAI" completely unprompted - not a response to
     * anything we sent - so there's nothing for [command]'s opcode-matching
     * logic to check against. A [StubLoader] implementation calls this right
     * after [memFinish] to look for that greeting.
     */
    suspend fun readRawFrame(timeoutMs: Long): ByteArray? = readFrame(timeoutMs)

    /**
     * The real, dedicated ERASE_FLASH command (0xD0) - hardware-level chip
     * erase, no data transfer at all. Only exists once a stub is running
     * (see [ChipType.supportsCompressedWrite]'s doc comment for the same
     * "stub-only" pattern on a different opcode); the permanent ROM has no
     * equivalent, which is the entire reason [FlashOperationManager]'s
     * ROM-only erase path has to fake it by writing a blank buffer instead.
     * Timeout is generous and flat rather than size-scaled - unlike the
     * ROM write-workaround, this command's duration depends on the flash
     * chip's own physical erase speed, not on how much data crosses the
     * serial link, so there's no data size to scale a timeout from.
     */
    suspend fun eraseFlashStub(): Unit {
        exec("erase flash (stub)", EspCommand.ERASE_FLASH, timeoutMs = 120_000L)
    }

    // ---------------------------------------------------------------------
    // Flashing (uncompressed)
    // ---------------------------------------------------------------------

    /**
     * Starts a flash write of [size] bytes at [offset]; the ROM (or stub)
     * erases the covered sectors immediately. [isStub] MUST reflect
     * whether a stub is currently running - it gates the ESP8266
     * ROM-erase-bug workaround in [FlashLayout.eraseSizeForFlashBegin],
     * which only applies to the bare ROM; passing the wrong value here for
     * a stub session silently under-erases (see that function's doc
     * comment for exactly what goes wrong and why it looks like a
     * successful write followed by an unrelated MD5 mismatch).
     */
    suspend fun flashBegin(c: ChipType, size: Long, offset: Long, isStub: Boolean = false): Int {
        val numBlocks = ((size + EspCommand.FLASH_WRITE_SIZE - 1) / EspCommand.FLASH_WRITE_SIZE).toInt()
        val eraseSize = FlashLayout.eraseSizeForFlashBegin(c, offset, size, isStub)
        var params = concat(
            u32le(eraseSize),
            u32le(numBlocks.toLong()),
            u32le(EspCommand.FLASH_WRITE_SIZE.toLong()),
            u32le(offset)
        )
        if (c != ChipType.ESP32 && c != ChipType.ESP8266) {
            params = concat(params, u32le(0)) // encrypted_write = false
        }
        val timeout = timeoutForSize(ERASE_TIMEOUT_PER_MB_MS, size)
        exec("flash begin", EspCommand.FLASH_BEGIN, params, timeoutMs = timeout)
        return numBlocks
    }

    suspend fun flashBlock(data: ByteArray, seq: Int, timeoutMs: Long = 10000, checksumOverride: Int? = null) {
        val header = concat(u32le(data.size.toLong()), u32le(seq.toLong()), u32le(0), u32le(0))
        val chk = checksumOverride ?: checksum(data)
        var lastError: FlasherException? = null
        repeat(WRITE_BLOCK_ATTEMPTS) {
            try {
                exec("flash data", EspCommand.FLASH_DATA, concat(header, data), chk = chk, timeoutMs = timeoutMs)
                return
            } catch (e: FlasherException) {
                if (!isRetryable(e)) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    /** Compressed counterpart of [flashBegin] - see that function's doc comment for [isStub]. */
    suspend fun flashDeflBegin(c: ChipType, uncompressedSize: Long, compressedSize: Long, offset: Long, isStub: Boolean = false): Int {
        val numBlocks = ((compressedSize + EspCommand.FLASH_WRITE_SIZE - 1) / EspCommand.FLASH_WRITE_SIZE).toInt()
        val eraseSize = FlashLayout.eraseSizeForFlashBegin(c, offset, uncompressedSize, isStub)
        var params = concat(
            u32le(eraseSize),
            u32le(numBlocks.toLong()),
            u32le(EspCommand.FLASH_WRITE_SIZE.toLong()),
            u32le(offset)
        )
        if (c != ChipType.ESP32 && c != ChipType.ESP8266) {
            params = concat(params, u32le(0))
        }
        val timeout = timeoutForSize(ERASE_TIMEOUT_PER_MB_MS, uncompressedSize)
        exec("compressed flash begin", EspCommand.FLASH_DEFL_BEGIN, params, timeoutMs = timeout)
        return numBlocks
    }

    suspend fun flashDeflBlock(data: ByteArray, seq: Int, timeoutMs: Long = 10000, checksumOverride: Int? = null) {
        val header = concat(u32le(data.size.toLong()), u32le(seq.toLong()), u32le(0), u32le(0))
        val chk = checksumOverride ?: checksum(data)
        var lastError: FlasherException? = null
        repeat(WRITE_BLOCK_ATTEMPTS) {
            try {
                exec("compressed flash data", EspCommand.FLASH_DEFL_DATA, concat(header, data), chk = chk, timeoutMs = timeoutMs)
                return
            } catch (e: FlasherException) {
                if (!isRetryable(e)) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    suspend fun flashFinish(reboot: Boolean, compressed: Boolean = false) {
        val op = if (compressed) EspCommand.FLASH_DEFL_END else EspCommand.FLASH_END
        val data = u32le(if (reboot) 0 else 1)
        exec(if (compressed) "compressed flash end" else "flash end", op, data)
    }

    /**
     * @param isStub whether a stub is currently running - MUST match reality,
     *   since the response shape genuinely differs and guessing wrong
     *   doesn't degrade gracefully. esptool's own `flash_md5sum()` sets
     *   `resp_data_len` to 32 (ASCII hex text) for the ROM but 16 (a raw
     *   binary digest, hex-formatted client-side) for the stub - this
     *   used to always assume the ROM shape. Under-declaring the length
     *   for a stub's actual 18-byte reply (16-byte digest + 2-byte status)
     *   made `exec()` read the first two bytes of the MD5 digest itself as
     *   a bogus status/reason pair - which is exactly what a "status=0x87
     *   reason=0x66 unknown ROM result code"-style error actually was.
     */
    suspend fun flashMd5(addr: Long, size: Long, isStub: Boolean): String {
        val timeout = timeoutForSize(MD5_TIMEOUT_PER_MB_MS, size)
        val respDataLen = if (isStub) 16 else 32
        val (_, data) = exec(
            "flash MD5",
            EspCommand.SPI_FLASH_MD5,
            concat(u32le(addr), u32le(size), u32le(0), u32le(0)),
            respDataLen = respDataLen,
            timeoutMs = timeout
        )
        return if (isStub) hex(data) else String(data, Charsets.US_ASCII).lowercase()
    }

    // ---------------------------------------------------------------------
    // ROM-only flash reading - only classic ESP32 exposes a real primitive
    // for this (READ_FLASH_SLOW). Every other chip's ROM has no flash-read
    // command at all; reading them requires a stub loader (see StubLoader).
    // ---------------------------------------------------------------------

    suspend fun supportsRomOnlyRead(c: ChipType): Boolean = c == ChipType.ESP32

    suspend fun readFlashRomOnly(offset: Long, length: Long, onProgress: ((Long, Long) -> Unit)? = null): ByteArray {
        val blockLen = 64
        val out = java.io.ByteArrayOutputStream(length.toInt().coerceAtLeast(0))
        var readSoFar = 0L
        while (readSoFar < length) {
            val thisLen = minOf(blockLen.toLong(), length - readSoFar).toInt()
            val (_, data) = exec(
                "read flash block",
                EspCommand.READ_FLASH_SLOW,
                concat(u32le(offset + readSoFar), u32le(thisLen.toLong())),
                respDataLen = blockLen
            )
            out.write(data, 0, thisLen)
            readSoFar += thisLen
            onProgress?.invoke(readSoFar, length)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------
    // Stub-only flash reading (READ_FLASH, 0xD2) - available once a stub is
    // running, on every chip a stub build exists for. Unlike
    // [readFlashRomOnly] (ESP32-ROM-only, no stub needed), this is the path
    // every other chip - ESP8266 included - needs for reading flash at all.
    // ---------------------------------------------------------------------

    /**
     * Reads flash via the stub's READ_FLASH command - a fundamentally
     * different exchange from every other command in this file, matching
     * esptool's own `ESPLoader.read_flash()` (the `IS_STUB` branch):
     *
     * 1. One normal command/response ([exec]) triggers the read and hands
     *    the stub its parameters (offset, length, a nominal block size of
     *    one flash sector, and a max-in-flight value).
     * 2. The stub then pushes raw SLIP frames of flash data completely
     *    unprompted, up to one sector (0x1000 bytes) each - NOT shaped like
     *    a normal 8-byte-header response, so these are read with
     *    [readRawFrame] the same way a stub's boot greeting is (see its own
     *    doc comment for why).
     * 3. After *every* frame, the host must echo back a bare SLIP frame
     *    containing a little-endian u32 of the *cumulative* bytes received
     *    so far - this is the read's entire flow-control mechanism. This is
     *    not a [command]/[exec]-shaped exchange either: no opcode, no
     *    header, just four raw bytes in a SLIP frame. Skipping or
     *    misforming it stalls the stub indefinitely.
     * 4. Once the requested length has been accumulated, one final raw SLIP
     *    frame carries a 16-byte MD5 digest of everything received, which
     *    must be verified locally - flash data has no per-frame checksum of
     *    its own the way FLASH_DATA/MEM_DATA writes do, so this digest is
     *    the only integrity check the whole read gets.
     *
     * maxInFlight is 64, matching esptool's own literal exactly - confirmed
     * against the actual esptool source: even esptool's reference client
     * acks after every single frame, in lockstep, never batching sends -
     * the window exists so the *device* can keep pushing bytes into its
     * UART TX path without stalling while the host writes its tiny ack,
     * not so the host can queue sends ahead. An earlier version of this
     * function reduced this to 1 as an experiment for the short/corrupt-
     * sector failures seen in testing; that didn't fix them and wasn't
     * based on anything the reference implementation actually does, so
     * it's reverted here rather than left as an unexplained deviation.
     */
    suspend fun readFlashStub(offset: Long, length: Long, onProgress: ((Long, Long) -> Unit)? = null): ByteArray {
        val sectorSize = 0x1000L
        val maxInFlight = 64L
        exec(
            "read flash",
            EspCommand.READ_FLASH,
            concat(u32le(offset), u32le(length), u32le(sectorSize), u32le(maxInFlight))
        )

        val out = java.io.ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0))
        var total = 0L
        while (total < length) {
            val frame = readRawFrame(timeoutMs = 3000)
                ?: throw FlasherException.FlashTimeout("read flash (no data frame within 3s)")
            out.write(frame)
            total += frame.size
            if (total < length && frame.size.toLong() < sectorSize) {
                // Show the tail of the short frame AND the transport's
                // recent raw-chunk timing in the error itself, not just a
                // bare "got N bytes" - if this fires, we want real numbers
                // (was there a gap before the last chunk? how many chunks
                // did this sector actually arrive in?) rather than another
                // round of guessing at what the USB layer was doing.
                val tail = frame.copyOfRange((frame.size - 16).coerceAtLeast(0), frame.size)
                val chunks = transport.recentReadChunks()
                val chunkLog = if (chunks.isEmpty()) {
                    "no chunk history available"
                } else {
                    chunks.mapIndexed { i, c ->
                        val gap = if (i == 0) 0L else c.timestampMs - chunks[i - 1].timestampMs
                        "size=${c.size} gap=${gap}ms"
                    }.joinToString(", ")
                }
                throw FlasherException.Generic(
                    "Corrupt data during flash read: expected a %d-byte sector but got %d bytes (last bytes: %s). Recent raw chunks: [%s]"
                        .format(sectorSize, frame.size, hex(tail), chunkLog)
                )
            }
            // Bare SLIP frame carrying the cumulative received byte count -
            // NOT a command()-shaped packet. This ack is what keeps the
            // stub sending; see the function doc comment above.
            transport.write(SlipCodec.encode(u32le(total)))
            onProgress?.invoke(total.coerceAtMost(length), length)
        }
        if (total > length) throw FlasherException.Generic("Read more data than requested during flash read.")

        val digestFrame = readRawFrame(timeoutMs = 3000)
            ?: throw FlasherException.FlashTimeout("read flash (no digest frame)")
        if (digestFrame.size != 16) {
            throw FlasherException.Generic("Expected a 16-byte MD5 digest after flash read, got ${digestFrame.size} bytes.")
        }
        val data = out.toByteArray()
        val actualDigest = java.security.MessageDigest.getInstance("MD5").digest(data)
        if (!actualDigest.contentEquals(digestFrame)) {
            throw FlasherException.ChecksumMismatch(hex(digestFrame), hex(actualDigest))
        }
        return data
    }
}
