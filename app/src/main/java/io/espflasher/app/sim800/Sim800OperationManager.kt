package io.espflasher.app.sim800

import io.espflasher.app.flashing.FlashCallback
import io.espflasher.app.flashing.LogLevel
import io.espflasher.app.flashing.ProgressState
import io.espflasher.app.flashing.ProgressTracker
import io.espflasher.app.protocol.FlasherException
import io.espflasher.app.usb.UsbSerialPort
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The SIM800 equivalent of [io.espflasher.app.ameba.AmebaOperationManager] -
 * same [FlashCallback]/[LogLevel]/[ProgressState] reporting conventions and
 * cancellation pattern, but driving [Sim800Protocol] instead, plus the
 * AT-command IMEI operations from `sim800_change_imei.py` (a completely
 * separate concern from ROM flashing - this talks to the module's normal
 * running firmware over AT commands, not its boot ROM). Doesn't own the USB
 * connection itself, same reasoning as Ameba's manager: [portProvider] reads
 * whatever port the existing connect/disconnect flow already has open, since
 * a SIM800 module connects over the exact same kind of USB-serial link ESP
 * and BW16 do.
 */
class Sim800OperationManager(
    private val portProvider: () -> UsbSerialPort?
) {
    companion object {
        /** How long [Sim800Protocol.sync] polls for - generous because, unlike ESP/BW16, getting here requires the person to physically power on/reset the module first. */
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val RESPONSE_TIMEOUT_MS = 15_000L

        /** How long an AT command is given to fully arrive before reading the reply - mirrors the reference script's `time.sleep(1)` between writing a command and reading its response. */
        private const val AT_SETTLE_MS = 1000L
    }

    private fun requirePort(): UsbSerialPort = portProvider() ?: throw FlasherException.UsbDisconnected()

    // -----------------------------------------------------------------
    // ROM flashing - ports sim800flasher2.py
    // -----------------------------------------------------------------

    suspend fun flashRom(rom: ByteArray, formatFat: Boolean, callback: FlashCallback) {
        if (rom.isEmpty()) throw FlasherException.Generic("The ROM file is empty.")
        val port = requirePort()
        val protocol = Sim800Protocol(port)

        callback.onLog(LogLevel.INFO, "Power on or reset the SIM800 module now - waiting for it to sync...")
        if (!protocol.sync(SYNC_TIMEOUT_MS)) {
            throw FlasherException.Generic(
                "No sync response from the SIM800 - check it's wired correctly and was actually powered on/reset into its boot ROM."
            )
        }
        callback.onLog(LogLevel.SUCCESS, "Device synced successfully")

        callback.onLog(LogLevel.COMMAND, if (formatFat) "-> DL_BEGIN_FORMAT" else "-> DL_BEGIN")
        if (!protocol.sendBegin(rom, formatFat, RESPONSE_TIMEOUT_MS)) {
            throw FlasherException.Generic("SIM800 didn't acknowledge DL_BEGIN - it may have dropped out of its boot ROM.")
        }
        callback.onLog(LogLevel.SUCCESS, "Starting flash write")

        var sent = minOf(Sim800Protocol.FIRST_CHUNK_SIZE, rom.size)
        val tracker = ProgressTracker(rom.size.toLong(), "Flashing SIM800 ROM")
        callback.onProgress(tracker.update(sent.toLong(), sent.toLong()))

        while (sent < rom.size) {
            currentCoroutineContext().ensureActive()
            val chunkSize = minOf(Sim800Protocol.DATA_CHUNK_SIZE, rom.size - sent)
            val chunk = rom.copyOfRange(sent, sent + chunkSize)
            if (!protocol.sendDataChunk(chunk, RESPONSE_TIMEOUT_MS)) {
                throw FlasherException.Generic("SIM800 didn't acknowledge the data chunk at byte $sent of ${rom.size}.")
            }
            sent += chunkSize
            callback.onProgress(tracker.update(sent.toLong(), sent.toLong()))
        }
        callback.onLog(LogLevel.SUCCESS, "ROM send done!")

        protocol.sendEndAndRun(RESPONSE_TIMEOUT_MS)
        callback.onProgress(ProgressState.IDLE)
        callback.onLog(LogLevel.SUCCESS, "Download and execution complete")
    }

    // -----------------------------------------------------------------
    // IMEI - ports sim800_change_imei.py. Talks AT commands to the module's
    // own running firmware, not the boot-ROM protocol above - needs a normal
    // connection (whatever baud the person's module expects for AT commands,
    // commonly 9600), not the flash sync sequence.
    // -----------------------------------------------------------------

    private suspend fun sendAtAndRead(port: UsbSerialPort, command: String): String {
        port.write((command + "\r").toByteArray(Charsets.US_ASCII))
        delay(AT_SETTLE_MS)
        val buf = ByteArray(64)
        val n = port.readBytes(buf, 200)
        return String(buf, 0, n, Charsets.UTF_8)
    }

    /** Pulls the first run of 14-16 digits out of a raw AT reply - the module's actual IMEI - falling back to the raw (trimmed) text if nothing digit-shaped is there to find. */
    private fun extractImei(rawResponse: String): String =
        Regex("\\d{14,16}").find(rawResponse)?.value ?: rawResponse.trim()

    /** Just `AT+GSN` - the module's current IMEI, with no attempt to change it. */
    suspend fun readImei(callback: FlashCallback): String {
        val port = requirePort()
        callback.onLog(LogLevel.COMMAND, "-> AT+GSN")
        val raw = sendAtAndRead(port, "AT+GSN")
        callback.onLog(LogLevel.RESPONSE, raw.ifBlank { "(no response)" })
        return extractImei(raw)
    }

    /**
     * The full read -> generate -> set -> verify sequence from
     * `sim800_change_imei.py`. [tac] is the 8-digit Type Allocation Code the
     * new IMEI is generated from (the reference script hardcodes "35581309";
     * here the person can pick it, defaulting to that same value - see the
     * "Change IMEI..." dialog). Returns (old IMEI, new IMEI as read back).
     */
    suspend fun changeImei(tac: String, callback: FlashCallback): Pair<String, String> {
        val port = requirePort()

        callback.onLog(LogLevel.COMMAND, "-> AT+GSN")
        val currentRaw = sendAtAndRead(port, "AT+GSN")
        callback.onLog(LogLevel.RESPONSE, currentRaw.ifBlank { "(no response)" })
        val currentImei = extractImei(currentRaw)
        callback.onLog(LogLevel.INFO, "Current IMEI: $currentImei")

        val newImei = Sim800Imei.findValid(tac)
        callback.onLog(LogLevel.INFO, "Generated new IMEI: $newImei")

        callback.onLog(LogLevel.COMMAND, "-> AT+SIMEI=$newImei")
        val setRaw = sendAtAndRead(port, "AT+SIMEI=$newImei")
        callback.onLog(LogLevel.RESPONSE, setRaw.ifBlank { "(no response)" })

        callback.onLog(LogLevel.COMMAND, "-> AT+GSN")
        val verifyRaw = sendAtAndRead(port, "AT+GSN")
        callback.onLog(LogLevel.RESPONSE, verifyRaw.ifBlank { "(no response)" })
        val newImeiVerified = extractImei(verifyRaw)
        callback.onLog(LogLevel.SUCCESS, "New IMEI: $newImeiVerified")

        return currentImei to newImeiVerified
    }
}
