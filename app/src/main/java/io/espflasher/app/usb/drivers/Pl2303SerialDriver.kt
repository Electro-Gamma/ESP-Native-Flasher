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
 * Prolific PL2303 driver.
 *
 * PL2303 silicon actually implements the same CDC line-coding wire format as
 * a standard CDC-ACM device for baud/parity/stop/data-bits (SET_LINE_REQUEST
 * = 0x20, SET_CONTROL_REQUEST = 0x22, both class/interface requests) - this
 * part is verified against the Linux pl2303.c request table.
 *
 * Real PL2303 host drivers additionally run a short vendor-specific register
 * poke sequence at open time before the chip will pass data reliably. That
 * sequence differs across the H/HX/TA/TB silicon revisions and Prolific has
 * never published it; the values below are the commonly-used "type 0/HX"
 * sequence seen across most open PL2303 host implementations. It's wrapped
 * defensively (failures are ignored) because some units work fine without it
 * and a handful of older/rarer revisions expect slightly different values -
 * if your specific adapter needs different magic bytes, this is the one
 * function to adjust.
 */
class Pl2303SerialDriver(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    endpointIn: UsbEndpoint,
    endpointOut: UsbEndpoint,
    info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : AbstractUsbSerialPort(connection, usbInterface, endpointIn, endpointOut, info, initialConfig) {

    private val interfaceNumber = usbInterface.id

    companion object {
        private const val SET_LINE_REQUEST_TYPE = 0x21
        private const val SET_LINE_REQUEST = 0x20
        private const val SET_CONTROL_REQUEST_TYPE = 0x21
        private const val SET_CONTROL_REQUEST = 0x22
        private const val CONTROL_DTR = 0x01
        private const val CONTROL_RTS = 0x02

        private const val VENDOR_WRITE_REQUEST_TYPE = 0x40
        private const val VENDOR_WRITE_REQUEST = 0x01
        private const val VENDOR_READ_REQUEST_TYPE = 0xC0
        private const val VENDOR_READ_REQUEST = 0x01

        private const val TIMEOUT_MS = 1000
    }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            connection.claimInterface(usbInterface, true)
            runCatching { runVendorInitSequence() }
        }
        applyConfig(config)
        setDtrRts(dtr = false, rts = false)
        markOpen()
    }

    override suspend fun close() {
        markClosed()
        withContext(Dispatchers.IO) {
            runCatching { connection.releaseInterface(usbInterface) }
            connection.close()
        }
    }

    private fun vendorRead(value: Int, index: Int) {
        val buf = ByteArray(1)
        connection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, value, index, buf, buf.size, TIMEOUT_MS)
    }

    private fun vendorWrite(value: Int, index: Int) {
        connection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, value, index, null, 0, TIMEOUT_MS)
    }

    private fun runVendorInitSequence() {
        vendorRead(0x8484, 0)
        vendorWrite(0x0404, 0)
        vendorRead(0x8484, 0)
        vendorRead(0x8383, 0)
        vendorRead(0x8484, 0)
        vendorWrite(0x0404, 1)
        vendorRead(0x8484, 0)
        vendorRead(0x8383, 0)
        vendorWrite(0, 1)
        vendorWrite(1, 0)
        vendorWrite(2, 0x24)
    }

    override suspend fun applyConfig(newConfig: SerialConfig) {
        withContext(Dispatchers.IO) {
            config = newConfig

            val stopBitsField = when (newConfig.stopBits) {
                StopBits.ONE -> 0
                StopBits.ONE_POINT_FIVE -> 1
                StopBits.TWO -> 2
            }
            val parityField = when (newConfig.parity) {
                Parity.NONE -> 0
                Parity.ODD -> 1
                Parity.EVEN -> 2
                Parity.MARK -> 3
                Parity.SPACE -> 4
            }
            val lineCoding = byteArrayOf(
                (newConfig.baudRate and 0xFF).toByte(),
                ((newConfig.baudRate ushr 8) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 16) and 0xFF).toByte(),
                ((newConfig.baudRate ushr 24) and 0xFF).toByte(),
                stopBitsField.toByte(),
                parityField.toByte(),
                newConfig.dataBits.value.toByte()
            )
            connection.controlTransfer(SET_LINE_REQUEST_TYPE, SET_LINE_REQUEST, 0, interfaceNumber, lineCoding, lineCoding.size, TIMEOUT_MS)
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
            val value = (if (dtr) CONTROL_DTR else 0) or (if (rts) CONTROL_RTS else 0)
            connection.controlTransfer(SET_CONTROL_REQUEST_TYPE, SET_CONTROL_REQUEST, value, interfaceNumber, null, 0, TIMEOUT_MS)
            Unit
        }
    }
}
