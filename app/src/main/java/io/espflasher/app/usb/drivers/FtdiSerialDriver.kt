package io.espflasher.app.usb.drivers

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.espflasher.app.usb.AbstractUsbSerialPort
import io.espflasher.app.usb.FlowControl
import io.espflasher.app.usb.Parity
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.StopBits
import io.espflasher.app.usb.UsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FTDI FT232R / FT232BM / FT2232C driver.
 *
 * Request codes and the sub-integer baud-rate divisor encoding follow FTDI's
 * own public application note (AN232B-05, "Configuring FT232R, FT2232 and
 * FT232B baud rates") - bits 13:0 of the encoded divisor are the integer
 * part, bits 16:15:14 select one of eight 1/8 fractional steps.
 */
class FtdiSerialDriver(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    endpointIn: UsbEndpoint,
    endpointOut: UsbEndpoint,
    info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : AbstractUsbSerialPort(connection, usbInterface, endpointIn, endpointOut, info, initialConfig) {

    companion object {
        private const val REQ_RESET = 0x00
        private const val REQ_MODEM_CTRL = 0x01
        private const val REQ_SET_FLOW_CTRL = 0x02
        private const val REQ_SET_BAUD_RATE = 0x03
        private const val REQ_SET_DATA = 0x04

        private const val TYPE_OUT = 0x40
        private const val TIMEOUT_MS = 1000
        private const val FTDI_BASE_CLOCK = 3_000_000L

        private val FRACTIONAL_STEP_TABLE = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)

        /** Returns (wValue, wIndex) for FTDI_SIO_SET_BAUDRATE, single-interface chips. */
        fun encodeBaudRate(baud: Int): Pair<Int, Int> {
            val clamped = baud.coerceIn(183, 3_000_000) // FT232R's documented supported range
            val divisor3 = (FTDI_BASE_CLOCK * 8) / clamped
            var divisor = (divisor3 shr 3).toInt()
            divisor = divisor or (FRACTIONAL_STEP_TABLE[(divisor3 and 0x7).toInt()] shl 14)
            if (divisor == 1) divisor = 0
            else if (divisor == 0x4001) divisor = 1
            val value = divisor and 0xFFFF
            val index = (divisor shr 16) and 0xFFFF
            return value to index
        }
    }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            connection.claimInterface(usbInterface, true)
            connection.controlTransfer(TYPE_OUT, REQ_RESET, 0, 0, null, 0, TIMEOUT_MS) // full reset
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

    override suspend fun applyConfig(newConfig: SerialConfig) {
        withContext(Dispatchers.IO) {
            config = newConfig

            val (baudValue, baudIndex) = encodeBaudRate(newConfig.baudRate)
            connection.controlTransfer(TYPE_OUT, REQ_SET_BAUD_RATE, baudValue, baudIndex, null, 0, TIMEOUT_MS)

            val parityField = when (newConfig.parity) {
                Parity.NONE -> 0
                Parity.ODD -> 1
                Parity.EVEN -> 2
                Parity.MARK -> 3
                Parity.SPACE -> 4
            }
            val stopBitsField = when (newConfig.stopBits) {
                StopBits.ONE -> 0
                StopBits.ONE_POINT_FIVE -> 1
                StopBits.TWO -> 2
            }
            val dataValue = newConfig.dataBits.value or (parityField shl 8) or (stopBitsField shl 11)
            connection.controlTransfer(TYPE_OUT, REQ_SET_DATA, dataValue, 0, null, 0, TIMEOUT_MS)

            val flowIndex = if (newConfig.flowControl == FlowControl.RTS_CTS) 0x0100 else 0x0000
            connection.controlTransfer(TYPE_OUT, REQ_SET_FLOW_CTRL, 0, flowIndex, null, 0, TIMEOUT_MS)
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
            // bit0=DTR state, bit1=RTS state, bit8=DTR enable mask, bit9=RTS enable mask.
            val value = (if (dtr) 0x0001 else 0x0000) or (if (rts) 0x0002 else 0x0000) or 0x0100 or 0x0200
            connection.controlTransfer(TYPE_OUT, REQ_MODEM_CTRL, value, 0, null, 0, TIMEOUT_MS)
            Unit
        }
    }
}
