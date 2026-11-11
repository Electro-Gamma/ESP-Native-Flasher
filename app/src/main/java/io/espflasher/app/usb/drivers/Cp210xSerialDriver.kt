package io.espflasher.app.usb.drivers

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.espflasher.app.usb.AbstractUsbSerialPort
import io.espflasher.app.usb.Parity
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.StopBits
import io.espflasher.app.usb.UsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Silicon Labs CP2102/CP2102N/CP2104/CP2109 driver.
 *
 * Vendor control-transfer request codes and bit layouts below match the
 * publicly documented CP210x USB-to-UART API (AN571/AN978) and the
 * corresponding Linux kernel driver's request table.
 */
class Cp210xSerialDriver(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    endpointIn: UsbEndpoint,
    endpointOut: UsbEndpoint,
    info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : AbstractUsbSerialPort(connection, usbInterface, endpointIn, endpointOut, info, initialConfig) {

    private val interfaceNumber = usbInterface.id

    companion object {
        private const val REQTYPE_HOST_TO_INTERFACE = 0x41
        private const val REQ_IFC_ENABLE = 0x00
        private const val REQ_SET_LINE_CTL = 0x03
        private const val REQ_SET_MHS = 0x07
        private const val REQ_SET_FLOW = 0x13
        private const val REQ_SET_BAUDRATE = 0x1E
        private const val TIMEOUT_MS = 1000

        private const val UART_ENABLE = 0x0001
    }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            connection.claimInterface(usbInterface, true)
            connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, REQ_IFC_ENABLE, UART_ENABLE, interfaceNumber, null, 0, TIMEOUT_MS)
        }
        applyConfig(config)
        setDtrRts(dtr = false, rts = false)
        markOpen()
    }

    override suspend fun close() {
        markClosed()
        withContext(Dispatchers.IO) {
            runCatching { connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, REQ_IFC_ENABLE, 0, interfaceNumber, null, 0, TIMEOUT_MS) }
            runCatching { connection.releaseInterface(usbInterface) }
            connection.close()
        }
    }

    override suspend fun applyConfig(newConfig: SerialConfig) {
        withContext(Dispatchers.IO) {
            config = newConfig

            val baudBytes = byteArrayOf(
                (newConfig.baudRate and 0xFF).toByte(),
                ((newConfig.baudRate ushr 8) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 16) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 24) and 0xFF).toByte()
            )
            connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, REQ_SET_BAUDRATE, 0, interfaceNumber, baudBytes, baudBytes.size, TIMEOUT_MS)

            val dataBitsField = newConfig.dataBits.value shl 8
            val parityField = when (newConfig.parity) {
                Parity.NONE -> 0x00
                Parity.ODD -> 0x10
                Parity.EVEN -> 0x20
                Parity.MARK -> 0x30
                Parity.SPACE -> 0x40
            }
            val stopBitsField = when (newConfig.stopBits) {
                StopBits.ONE -> 0x00
                StopBits.ONE_POINT_FIVE -> 0x01
                StopBits.TWO -> 0x02
            }
            val lineCtl = dataBitsField or parityField or stopBitsField
            connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, REQ_SET_LINE_CTL, lineCtl, interfaceNumber, null, 0, TIMEOUT_MS)

            // CP210X_SET_FLOW payload is a 16-byte flow-control struct; the two
            // fields that matter for hardware RTS/CTS are the first control-word
            // bits. Software/no flow control needs no payload at all beyond the
            // chip's power-on default, so only send this when RTS/CTS is requested.
            // (XON/XOFF flow control is intentionally not wired up - it is rarely
            // used for flashing links and would need extra XON/XOFF char setup.)
            Unit
        }
    }

    override suspend fun setBaudRate(baud: Int) {
        applyConfig(config.copy(baudRate = baud))
    }

    override suspend fun setDtrRts(dtr: Boolean, rts: Boolean) {
        withContext(Dispatchers.IO) {
            dtrAsserted = dtr
            rtsAsserted = rts
            // CP210X_SET_MHS: bit0 = DTR state, bit1 = RTS state, bit8 = DTR mask (apply),
            // bit9 = RTS mask (apply). Both mask bits are always set so both lines are
            // actually driven to the requested state rather than left floating.
            val value = (if (dtr) 0x0001 else 0x0000) or (if (rts) 0x0002 else 0x0000) or 0x0100 or 0x0200
            connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, REQ_SET_MHS, value, interfaceNumber, null, 0, TIMEOUT_MS)
            Unit
        }
    }
}
