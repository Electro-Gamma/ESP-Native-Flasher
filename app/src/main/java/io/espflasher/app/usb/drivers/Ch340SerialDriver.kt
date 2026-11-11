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
 * WCH CH340/CH341 driver, the single most common bridge on budget ESP dev
 * boards. The vendor request codes, register map, and baud-rate divisor
 * algorithm below are taken from the current upstream Linux kernel driver
 * (`drivers/usb/serial/ch341.c`) - i.e. the hardware's actual documented
 * behaviour, not any flashing tool's source.
 *
 * Older CH340 revisions only support 8N1 reliably (the real hardware
 * limitation the kernel driver itself works around by gating LCR writes on
 * chip version >= 0x30); this driver follows the same version gate rather
 * than silently failing on those parts.
 */
class Ch340SerialDriver(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    endpointIn: UsbEndpoint,
    endpointOut: UsbEndpoint,
    info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : AbstractUsbSerialPort(connection, usbInterface, endpointIn, endpointOut, info, initialConfig) {

    private var chipVersion: Int = 0x30 // optimistic default until READ_VERSION responds

    companion object {
        private const val REQ_READ_VERSION = 0x5F
        private const val REQ_WRITE_REG = 0x9A
        private const val REQ_SERIAL_INIT = 0xA1
        private const val REQ_MODEM_CTRL = 0xA4

        private const val REG_PRESCALER = 0x12
        private const val REG_DIVISOR = 0x13
        private const val REG_LCR = 0x18
        private const val REG_LCR2 = 0x25
        private const val REG_FLOW_CTL = 0x27

        private const val LCR_ENABLE_RX = 0x80
        private const val LCR_ENABLE_TX = 0x40
        private const val LCR_PAR_EVEN = 0x10
        private const val LCR_ENABLE_PAR = 0x08
        private const val LCR_STOP_BITS_2 = 0x04
        private const val LCR_CS8 = 0x03
        private const val LCR_CS7 = 0x02
        private const val LCR_CS6 = 0x01
        private const val LCR_CS5 = 0x00

        private const val BIT_RTS = 1 shl 6
        private const val BIT_DTR = 1 shl 5

        private const val CLK_RATE = 48_000_000L

        private const val TYPE_VENDOR_OUT = 0x40
        private const val TYPE_VENDOR_IN = 0xC0
        private const val TIMEOUT_MS = 1000

        private fun clkDiv(ps: Int, fact: Int): Long = 1L shl (12 - 3 * ps - fact)
        private fun minRate(ps: Int): Long = CLK_RATE / (clkDiv(ps, 1) * 512)
        private val MIN_BPS = (CLK_RATE + (clkDiv(0, 0) * 256) - 1) / (clkDiv(0, 0) * 256)
        private val MAX_BPS = CLK_RATE / (clkDiv(3, 0) * 2)

        /** Faithful port of ch341_get_divisor(): returns the encoded (div,fact,ps) word, or null if out of range. */
        fun computeDivisorWord(requestedBaud: Int): Int? {
            val speed = requestedBaud.toLong().coerceIn(MIN_BPS, MAX_BPS)
            var fact = 1
            var ps = 3
            while (ps >= 0) {
                if (speed > minRate(ps)) break
                ps--
            }
            if (ps < 0) return null

            var clkDivVal = clkDiv(ps, fact)
            var div = CLK_RATE / (clkDivVal * speed)

            if (div < 9 || div > 255) {
                div /= 2
                clkDivVal *= 2
                fact = 0
            }
            if (div < 2) return null

            val a = 16 * CLK_RATE / (clkDivVal * div) - 16 * speed
            val b = 16 * speed - 16 * CLK_RATE / (clkDivVal * (div + 1))
            if (a >= b) div++

            if (fact == 1 && div % 2 == 0L) {
                div /= 2
                fact = 0
            }
            return (((0x100 - div).toInt() and 0xFF) shl 8) or (fact shl 2) or ps
        }
    }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            connection.claimInterface(usbInterface, true)

            val versionBuf = ByteArray(2)
            val n = connection.controlTransfer(TYPE_VENDOR_IN, REQ_READ_VERSION, 0, 0, versionBuf, versionBuf.size, TIMEOUT_MS)
            if (n >= 1) chipVersion = versionBuf[0].toInt() and 0xFF

            connection.controlTransfer(TYPE_VENDOR_OUT, REQ_SERIAL_INIT, 0, 0, null, 0, TIMEOUT_MS)
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

            val divisorWord = computeDivisorWord(newConfig.baudRate)
                ?: computeDivisorWord(115200)!! // fall back rather than silently doing nothing
            val withBufferBit = if (chipVersion > 0x27) (divisorWord or 0x80) else divisorWord
            connection.controlTransfer(
                TYPE_VENDOR_OUT, REQ_WRITE_REG, (REG_DIVISOR shl 8) or REG_PRESCALER, withBufferBit, null, 0, TIMEOUT_MS
            )

            if (chipVersion >= 0x30) {
                var lcr = LCR_ENABLE_RX or LCR_ENABLE_TX
                lcr = lcr or when (newConfig.dataBits.value) {
                    5 -> LCR_CS5
                    6 -> LCR_CS6
                    7 -> LCR_CS7
                    else -> LCR_CS8
                }
                if (newConfig.parity != Parity.NONE) {
                    lcr = lcr or LCR_ENABLE_PAR
                    if (newConfig.parity == Parity.EVEN) lcr = lcr or LCR_PAR_EVEN
                }
                if (newConfig.stopBits == StopBits.TWO) lcr = lcr or LCR_STOP_BITS_2
                connection.controlTransfer(TYPE_VENDOR_OUT, REQ_WRITE_REG, (REG_LCR2 shl 8) or REG_LCR, lcr, null, 0, TIMEOUT_MS)
            }
            // Older (< 0x30) silicon only reconfigures the divisor; framing stays 8N1 in hardware.

            val flowCtl = if (newConfig.flowControl == FlowControl.RTS_CTS) 1 else 0
            connection.controlTransfer(
                TYPE_VENDOR_OUT, REQ_WRITE_REG, (REG_FLOW_CTL shl 8) or REG_FLOW_CTL, (flowCtl shl 8) or flowCtl, null, 0, TIMEOUT_MS
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
            val control = (if (dtr) BIT_DTR else 0) or (if (rts) BIT_RTS else 0)
            val value = control.inv() and 0xFF
            connection.controlTransfer(TYPE_VENDOR_OUT, REQ_MODEM_CTRL, value, 0, null, 0, TIMEOUT_MS)
            Unit
        }
    }
}
