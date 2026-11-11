package io.espflasher.app.usb.drivers

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.espflasher.app.usb.AbstractUsbSerialPort
import io.espflasher.app.usb.DataBits
import io.espflasher.app.usb.Parity
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.StopBits
import io.espflasher.app.usb.UsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard USB CDC-ACM class driver (USB-IF "Communications Device Class").
 *
 * Uses only the two class requests every compliant CDC-ACM device supports:
 * SET_LINE_CODING (0x20) and SET_CONTROL_LINE_STATE (0x22), both sent with
 * bmRequestType 0x21 (host-to-device, class, interface). This is what
 * Espressif's own native USB-Serial/JTAG peripheral (built into ESP32-S2,
 * S3, C3, C6, H2) presents as, so this driver also covers "no external
 * adapter" boards.
 */
class CdcAcmSerialDriver(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    private val controlInterface: UsbInterface,
    endpointIn: UsbEndpoint,
    endpointOut: UsbEndpoint,
    info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : AbstractUsbSerialPort(connection, usbInterface, endpointIn, endpointOut, info, initialConfig) {

    private val controlInterfaceNumber = controlInterface.id

    companion object {
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val REQ_TYPE_OUT = 0x21 // host->device, class, interface
        private const val TIMEOUT_MS = 1000
    }

    override suspend fun open() {
        connection.claimInterface(controlInterface, true)
        if (controlInterface.id != usbInterface.id) {
            connection.claimInterface(usbInterface, true)
        }
        applyConfig(config)
        setDtrRts(dtr = false, rts = false)
        markOpen()
    }

    override suspend fun close() {
        markClosed()
        withContext(Dispatchers.IO) {
            runCatching { connection.releaseInterface(controlInterface) }
            if (controlInterface.id != usbInterface.id) {
                runCatching { connection.releaseInterface(usbInterface) }
            }
            connection.close()
        }
    }

    override suspend fun applyConfig(newConfig: SerialConfig) {
        withContext(Dispatchers.IO) {
            config = newConfig
            val stopBitsCode = when (newConfig.stopBits) {
                StopBits.ONE -> 0
                StopBits.ONE_POINT_FIVE -> 1
                StopBits.TWO -> 2
            }
            val parityCode = when (newConfig.parity) {
                Parity.NONE -> 0
                Parity.ODD -> 1
                Parity.EVEN -> 2
                Parity.MARK -> 3
                Parity.SPACE -> 4
            }
            val dataBits = newConfig.dataBits.value

            val lineCoding = byteArrayOf(
                (newConfig.baudRate and 0xFF).toByte(),
                ((newConfig.baudRate ushr 8) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 16) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 24) and 0xFF).toByte(),
                stopBitsCode.toByte(),
                parityCode.toByte(),
                dataBits.toByte()
            )
            connection.controlTransfer(
                REQ_TYPE_OUT, SET_LINE_CODING, 0, controlInterfaceNumber, lineCoding, lineCoding.size, TIMEOUT_MS
            )
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
            val value = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
            connection.controlTransfer(REQ_TYPE_OUT, SET_CONTROL_LINE_STATE, value, controlInterfaceNumber, null, 0, TIMEOUT_MS)
            Unit
        }
    }

    /** Locates the CDC data interface's bulk IN/OUT endpoints. */
    object Discovery {
        fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            return if (inEp != null && outEp != null) inEp to outEp else null
        }
    }
}
