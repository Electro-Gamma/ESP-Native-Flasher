package io.espflasher.app.usb

/** Number of data bits per UART frame. */
enum class DataBits(val value: Int) { FIVE(5), SIX(6), SEVEN(7), EIGHT(8) }

enum class StopBits(val label: String) { ONE("1"), ONE_POINT_FIVE("1.5"), TWO("2") }

enum class Parity(val label: String) { NONE("None"), ODD("Odd"), EVEN("Even"), MARK("Mark"), SPACE("Space") }

enum class FlowControl(val label: String) { NONE("None"), RTS_CTS("RTS/CTS"), XON_XOFF("XON/XOFF") }

/** Everything needed to configure a UART link. Defaults match esptool's usual 115200 8N1. */
data class SerialConfig(
    val baudRate: Int = 115200,
    val dataBits: DataBits = DataBits.EIGHT,
    val stopBits: StopBits = StopBits.ONE,
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE
) {
    companion object {
        val COMMON_BAUD_RATES = listOf(9600, 57600, 115200, 230400, 460800, 921600, 1500000, 2000000)
    }
}
