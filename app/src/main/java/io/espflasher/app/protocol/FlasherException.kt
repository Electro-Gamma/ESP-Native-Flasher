package io.espflasher.app.protocol

/**
 * Base type for every error this app can raise, so the UI layer can show a
 * meaningful, specific message instead of a generic failure.
 */
sealed class FlasherException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class UsbDisconnected :
        FlasherException("USB device was disconnected.")

    class PermissionDenied(deviceName: String) :
        FlasherException("USB permission denied for $deviceName. Reconnect the device and accept the permission prompt.")

    class SyncFailed(attempts: Int) :
        FlasherException("Failed to sync with the chip after $attempts attempts. Hold BOOT/IO0 while resetting, or check wiring and baud rate.")

    class InvalidFirmware(reason: String) :
        FlasherException("Invalid firmware image: $reason")

    class FlashTimeout(operation: String) :
        FlasherException("Timed out waiting for a response during $operation.")

    class UnsupportedChip(detail: String) :
        FlasherException("Could not identify a supported Espressif chip ($detail).")

    class InvalidAddress(address: Long) :
        FlasherException("Invalid flash address: 0x%08X".format(address))

    class ChecksumMismatch(expected: String, actual: String) :
        FlasherException("Checksum mismatch after write: expected $expected, got $actual.")

    class RomError(op: String, statusByte: Int, reasonByte: Int) :
        FlasherException(
            "ROM rejected '$op': ${romErrorMeaning(statusByte, reasonByte)} " +
                "(status=0x%02X reason=0x%02X)".format(statusByte, reasonByte)
        )

    class NoDeviceFound :
        FlasherException("No supported USB-UART bridge was found. Plug in a device and grant USB permission.")

    class OperationCancelled :
        FlasherException("Operation cancelled.")

    class Generic(message: String, cause: Throwable? = null) : FlasherException(message, cause)

    companion object {
        /**
         * The two status bytes every ROM command response ends with combine into
         * one big-endian 16-bit code with a documented meaning (e.g. 0x0106 =
         * "message is ok, but the running result is wrong"). Decoding it gives a
         * far more useful message than the raw hex ever does on its own.
         */
        fun romErrorMeaning(statusByte: Int, reasonByte: Int): String {
            val code = ((statusByte and 0xFF) shl 8) or (reasonByte and 0xFF)
            return ROM_ERROR_MEANINGS[code] ?: "unknown ROM result code"
        }

        private val ROM_ERROR_MEANINGS: Map<Int, String> = mapOf(
            0x0100 to "undefined error",
            0x0101 to "invalid input parameter",
            0x0102 to "ROM failed to allocate memory",
            0x0103 to "ROM failed to send a response",
            0x0104 to "ROM failed to receive the message",
            0x0105 to "the message format was invalid",
            0x0106 to "message was understood, but executing it failed " +
                "(commonly: flash parameters not set - try Detect again before Erase/Flash)",
            0x0107 to "checksum error",
            0x0108 to "flash write error",
            0x0109 to "flash read error",
            0x010A to "flash read length error",
            0x010B to "compressed-data inflate error",
            0x010C to "compressed-data Adler32 checksum error",
            0x010D to "compressed-data parameter error",
            0x010E to "invalid RAM binary size",
            0x010F to "invalid RAM binary address"
        )
    }
}
