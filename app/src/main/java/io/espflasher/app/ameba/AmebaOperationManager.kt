package io.espflasher.app.ameba

import android.content.Context
import io.espflasher.app.flashing.FlashCallback
import io.espflasher.app.flashing.LogLevel
import io.espflasher.app.flashing.ProgressState
import io.espflasher.app.flashing.ProgressTracker
import io.espflasher.app.protocol.FlasherException
import io.espflasher.app.usb.UsbSerialPort
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The BW16/AmebaD equivalent of [io.espflasher.app.flashing.FlashOperationManager] -
 * same [FlashCallback]/[LogLevel]/[ProgressState] reporting conventions, same
 * cancellation pattern (checks `ensureActive()` between images so Stop takes
 * effect promptly), but talking a completely different wire protocol via
 * [AmebaProtocol]. Doesn't own the USB connection itself - [portProvider]
 * reads whatever port the existing connect/disconnect flow already has open,
 * since a BW16 and an ESP32 both connect over the exact same kind of
 * USB-serial link and there's no reason to duplicate that lifecycle.
 */
class AmebaOperationManager(
    private val context: Context,
    private val portProvider: () -> UsbSerialPort?
) {
    private fun requirePort(): UsbSerialPort = portProvider() ?: throw FlasherException.UsbDisconnected()

    private fun loadFlashloader(): ByteArray = try {
        context.assets.open("ameba/flashloader.bin").use { it.readBytes() }
    } catch (e: Exception) {
        throw FlasherException.Generic("Couldn't read the bundled BW16 flashloader: ${e.message}")
    }

    suspend fun eraseFlash(images: List<AmebaImage>, resetAfter: Boolean, callback: FlashCallback) {
        run(images, eraseOnly = true, uploadBaudRate = 115200, resetAfter, callback)
    }

    suspend fun flashImages(images: List<AmebaImage>, uploadBaudRate: Int, resetAfter: Boolean, callback: FlashCallback) {
        run(images, eraseOnly = false, uploadBaudRate, resetAfter, callback)
    }

    suspend fun resetDevice(callback: FlashCallback) {
        AmebaProtocol(requirePort()).resetToRun()
        callback.onLog(LogLevel.INFO, "Reset - BW16 is now running its flashed application")
    }

    suspend fun enterDownloadMode(callback: FlashCallback) {
        AmebaProtocol(requirePort()).enterDownloadMode()
        callback.onLog(LogLevel.INFO, "Reset into BW16 download mode")
    }

    private suspend fun run(images: List<AmebaImage>, eraseOnly: Boolean, uploadBaudRate: Int, resetAfter: Boolean, callback: FlashCallback) {
        if (images.isEmpty()) throw FlasherException.Generic("No BW16 images selected.")
        val port = requirePort()
        val protocol = AmebaProtocol(port)
        val flashloader = loadFlashloader()

        callback.onLog(LogLevel.INFO, "Resetting into BW16 download mode...")
        protocol.enterDownloadMode()
        protocol.flushSerial()

        callback.onLog(LogLevel.COMMAND, "-> SYNC")
        if (!protocol.waitSync(2)) {
            throw FlasherException.Generic("No sync response from the BW16 - check it's wired correctly and the download-mode reset actually reached it.")
        }
        callback.onLog(LogLevel.SUCCESS, "<- Sync OK")

        if (!protocol.setMaxSpeed(uploadBaudRate)) throw FlasherException.Generic("Baud rate negotiation failed before the flashloader upload.")

        callback.onLog(LogLevel.COMMAND, "Uploading flashloader (${flashloader.size} bytes)...")
        val (loaderOk, _) = protocol.writeBlock(AmebaProtocol.FLASHLOADER_ADDR, flashloader, seqIdStart = 1)
        if (!loaderOk) throw FlasherException.Generic("Flashloader upload failed.")

        if (!protocol.sendCmd(byteArrayOf(0x04))) throw FlasherException.Generic("Flashloader failed to start.")
        port.setBaudRate(115200)
        if (!protocol.waitSync(2)) throw FlasherException.Generic("No response from the flashloader after starting it.")
        callback.onLog(LogLevel.SUCCESS, "Flashloader running.")

        if (!protocol.sendCmd(byteArrayOf(0x26, 0x01, 0x01, 0x00))) throw FlasherException.Generic("SPI flash init failed.")

        val eraseTracker = ProgressTracker(images.sumOf { it.data.size.toLong() }, "Erasing BW16")
        var eraseBytesDone = 0L
        for (image in images) {
            currentCoroutineContext().ensureActive()
            callback.onLog(LogLevel.COMMAND, "Erasing ${image.label} (0x%X, %d bytes)...".format(image.offset, image.data.size))
            if (!protocol.eraseCommand(image.offset, image.data.size.toLong())) {
                throw FlasherException.Generic("Erase failed for ${image.label}.")
            }
            // eraseCommand itself is a single opaque round-trip (no per-byte
            // feedback the way a write has), but with 3 images to erase this
            // at least moves the bar between them instead of leaving it dead
            // the whole time an erase-before-write is running.
            eraseBytesDone += image.data.size
            callback.onProgress(eraseTracker.update(eraseBytesDone, image.offset))
        }

        if (eraseOnly) {
            callback.onLog(LogLevel.SUCCESS, "Erase complete.")
            callback.onProgress(ProgressState.IDLE)
            if (resetAfter) resetDevice(callback)
            return
        }
        callback.onLog(LogLevel.SUCCESS, "Erase complete - writing...")

        if (!protocol.waitSync(1)) throw FlasherException.Generic("Lost sync before write.")
        if (!protocol.setMaxSpeed(uploadBaudRate)) throw FlasherException.Generic("Baud rate negotiation failed before write.")

        var seqId = 1
        val totalBytes = images.sumOf { it.data.size.toLong() }
        var bytesDone = 0L
        val tracker = ProgressTracker(totalBytes, "Flashing BW16")
        for (image in images) {
            currentCoroutineContext().ensureActive()
            callback.onLog(LogLevel.COMMAND, "Writing ${image.label} at 0x%X (${image.data.size} bytes)...".format(image.offset))
            val imageBytesDone = bytesDone
            val (ok, nextSeq) = protocol.writeBlock(image.offset, image.data, seqId) { chunksDone, _ ->
                // writeBlock always pads up to a whole 1024-byte chunk, so
                // clamp the last chunk's contribution to the image's real
                // size instead of over-reporting past 100%.
                val chunkBytes = (chunksDone.toLong() * 1024L).coerceAtMost(image.data.size.toLong())
                callback.onProgress(tracker.update(imageBytesDone + chunkBytes, image.offset))
            }
            seqId = nextSeq
            if (!ok) throw FlasherException.Generic("Write failed for ${image.label}.")
            bytesDone += image.data.size
            callback.onProgress(tracker.update(bytesDone, image.offset))
        }

        if (!protocol.sendCmd(byteArrayOf(0x04))) throw FlasherException.Generic("Failed to hand off after writing.")
        port.setBaudRate(115200)
        protocol.flushSerial()
        if (!protocol.waitSync(1)) throw FlasherException.Generic("Lost sync before verify.")

        for (image in images) {
            currentCoroutineContext().ensureActive()
            callback.onLog(LogLevel.COMMAND, "Verifying ${image.label}...")
            val expected = protocol.fileChecksum(image.data)
            if (!protocol.verifyBlock(image.offset, image.data.size, expected)) {
                throw FlasherException.Generic("Verification failed for ${image.label} - written data doesn't match what was sent.")
            }
            callback.onLog(LogLevel.SUCCESS, "${image.label} verified OK.")
        }

        callback.onProgress(ProgressState.IDLE)
        callback.onLog(LogLevel.SUCCESS, "BW16 flash complete.")
        if (resetAfter) resetDevice(callback)
    }
}
