package io.espflasher.app.arduinohex

import android.hardware.usb.UsbDevice

/**
 * Best-effort, friendly label for the connected USB-serial adapter, purely for display in the
 * status row. This does NOT gate which devices the app will try to talk to — the felHR85
 * UsbSerial driver used by the upload library auto-detects the chip type (or falls back to
 * generic USB-CDC) at connection time regardless of what's returned here.
 */
object UsbChipIdentifier {

    fun friendlyName(device: UsbDevice): String {
        val vid = device.vendorId
        val pid = device.productId
        return when (vid) {
            0x1A86 -> "CH340/CH341 serial adapter"
            0x10C4, 0x10C5 -> "CP210x (Silicon Labs) serial adapter"
            0x0403 -> "FTDI FT232/FT230X serial adapter"
            0x067B -> "PL2303 (Prolific) serial adapter"
            0x2341, 0x2A03 -> arduinoBoardName(pid) ?: "Arduino-compatible board"
            0x239A -> "Adafruit board"
            0x1B4F -> "SparkFun board"
            else -> device.productName?.takeIf { it.isNotBlank() }
                ?: "USB-serial device (VID ${"%04X".format(vid)}:${"%04X".format(pid)})"
        }
    }

    private fun arduinoBoardName(pid: Int): String? = when (pid) {
        0x0043, 0x0001, 0x0243 -> "Arduino Uno (native USB-CDC)"
        0x0010, 0x0042 -> "Arduino Mega 2560 (native USB-CDC)"
        0x0036, 0x0037, 0x8036, 0x8037 -> "Arduino Leonardo/Micro (native USB-CDC)"
        else -> null
    }
}
