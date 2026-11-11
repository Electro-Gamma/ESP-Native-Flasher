package io.espflasher.app.usb

/** Which USB-UART bridge silicon a device uses, so the right control-transfer dialect is picked. */
enum class ChipFamily(val label: String) {
    CP210X("Silicon Labs CP210x"),
    CH340("WCH CH340/CH341"),
    FTDI("FTDI FT232/FT230X"),
    PL2303("Prolific PL2303"),
    CDC_ACM("USB CDC-ACM (incl. native ESP32 USB-Serial/JTAG)"),
    UNKNOWN("Unknown")
}

/** Snapshot of a USB device's identity, shown in the Connection panel. */
data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val androidDeviceName: String,
    val chipFamily: ChipFamily
) {
    val vidPidHex: String get() = "%04X:%04X".format(vendorId, productId)
    val displayLabel: String get() = productName?.takeIf { it.isNotBlank() } ?: chipFamily.label
}
