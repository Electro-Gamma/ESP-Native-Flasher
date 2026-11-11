package kr.co.makeitall.arduino.usbasp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

class UsbaspException(message: String) : Exception(message)

/**
 * Low-level USBasp driver. USBasp is a raw-USB (vendor-class control transfer) AVR ISP
 * programmer, not a USB-serial device — none of felHR85/UsbSerialManager applies here.
 *
 * Paged flash/EEPROM access is a direct, line-for-line port of avrdude 8.2's src/usbasp.c
 * (usbasp_transmit / usbasp_open / usbasp_initialize / usbasp_spi_program_enable /
 * usbasp_spi_chip_erase / usbasp_spi_paged_write / usbasp_spi_paged_load /
 * usbasp_spi_set_sck_period). The single-byte fuse/lock/signature/calibration reads use the
 * standard AVR "Serial Programming Instruction Set" opcodes, verified against avrdude.conf's
 * .classic/.classic-nocal base templates (shared by the entire classic ISP-capable AVR8 line)
 * rather than reconstructed from memory. SPI/ISP mode only: TPI (attiny4/5/9/10 series) is out
 * of scope, since none of this app's supported parts use it.
 *
 * Wire format (see usbasp_transmit in avrdude): every call is a USB control transfer with
 * bmRequestType = vendor + device-recipient (0xC0 for device-to-host, 0x40 for host-to-device),
 * bRequest = the USBASP_FUNC_* id, and a 4-byte "command" packed into wValue (low 16 bits) /
 * wIndex (high 16 bits). Bulk data (flash/EEPROM bytes, the 4-byte ISP echo) rides in the
 * transfer's data stage.
 */
class UsbaspProgrammer(context: Context, private val device: UsbDevice) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null

    /** Claims the USBasp's single interface and opens the connection. */
    fun open(): Boolean {
        val conn = usbManager.openDevice(device) ?: return false
        if (device.interfaceCount <= 0) {
            conn.close()
            return false
        }
        val iface = device.getInterface(0)
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            return false
        }
        connection = conn
        return true
    }

    /** usbasp_close(): best-effort DISCONNECT, then release + close. */
    fun close() {
        val conn = connection ?: return
        try {
            transmitRaw(conn, receive = true, function = UsbaspFunction.DISCONNECT, send = ZERO4, buffer = ByteArray(4), length = 4)
        } catch (_: Exception) {
            // Best-effort — the device may already be gone.
        }
        try {
            conn.releaseInterface(device.getInterface(0))
        } catch (_: Exception) {
        }
        conn.close()
        connection = null
    }

    /** usbasp_initialize()'s CONNECT step + its 100ms settle wait. */
    fun connect() {
        transmit(receive = true, function = UsbaspFunction.CONNECT, send = ZERO4, buffer = ByteArray(4), length = 4)
        Thread.sleep(100)
    }

    /** usbasp_spi_set_sck_period(). */
    fun setSck(option: UsbaspSckOption) {
        val send = byteArrayOf(option.id.toByte(), 0, 0, 0)
        val res = ByteArray(4)
        val n = transmit(receive = true, function = UsbaspFunction.SETISPSCK, send = send, buffer = res, length = 4)
        if (n < 1 || res[0].toInt() != 0) {
            throw UsbaspException("USBasp did not accept that SCK speed — try Auto, or a slower speed")
        }
    }

    /** usbasp_spi_program_enable(). Must succeed before any read/write/erase. */
    fun enableProgramming() {
        val res = ByteArray(4)
        val n = transmit(receive = true, function = UsbaspFunction.ENABLEPROG, send = ZERO4, buffer = res, length = 4)
        if (n != 1 || res[0].toInt() != 0) {
            throw UsbaspException(
                "Target did not respond to programming enable — check wiring (MOSI/MISO/SCK/RESET/GND) and that the board is powered"
            )
        }
    }

    /** usbasp_spi_cmd(): a raw 4-byte ISP command via TRANSMIT. Returns the 4-byte SPI echo. */
    fun spiCommand(cmd: ByteArray): ByteArray {
        require(cmd.size == 4) { "ISP commands are always 4 bytes" }
        val res = ByteArray(4)
        val n = transmit(receive = true, function = UsbaspFunction.TRANSMIT, send = cmd, buffer = res, length = 4)
        if (n != 4) throw UsbaspException("USBasp gave a short response to an ISP command")
        return res
    }

    // region Single-byte reads (standard AVR ISP opcodes, verified against avrdude.conf)

    /** Read Signature Byte: 0x30 0x00 addr 0x00 -> echo[3], addr 0..2. */
    fun readSignature(): IntArray = IntArray(3) { addr ->
        spiCommand(byteArrayOf(0x30, 0x00, addr.toByte(), 0x00))[3].toInt() and 0xFF
    }

    /** Read Calibration Byte: 0x38 0x00 0x00 0x00 -> echo[3]. */
    fun readCalibration(): Int = spiCommand(byteArrayOf(0x38, 0x00, 0x00, 0x00))[3].toInt() and 0xFF

    /** Read Lock Bits: 0x58 0x00 0x00 0x00 -> echo[3]. */
    fun readLock(): Int = spiCommand(byteArrayOf(0x58.toByte(), 0x00, 0x00, 0x00))[3].toInt() and 0xFF

    /** Read Fuse Bits (low fuse): 0x50 0x00 0x00 0x00 -> echo[3]. */
    fun readLFuse(): Int = spiCommand(byteArrayOf(0x50, 0x00, 0x00, 0x00))[3].toInt() and 0xFF

    /** Read Fuse High Bits: 0x58 0x08 0x00 0x00 -> echo[3]. */
    fun readHFuse(): Int = spiCommand(byteArrayOf(0x58.toByte(), 0x08, 0x00, 0x00))[3].toInt() and 0xFF

    /** Read Extended Fuse Bits: 0x50 0x08 0x00 0x00 -> echo[3]. */
    fun readEFuse(): Int = spiCommand(byteArrayOf(0x50, 0x08, 0x00, 0x00))[3].toInt() and 0xFF

    // endregion

    /** usbasp_spi_chip_erase(): standard AVR ISP "Chip Erase" (0xAC 0x80 0x00 0x00). */
    fun chipErase(chipEraseDelayMs: Long = 9) {
        spiCommand(byteArrayOf(0xAC.toByte(), 0x80.toByte(), 0x00, 0x00))
        Thread.sleep(chipEraseDelayMs)
    }

    private fun setLongAddress(address: Int) {
        val send = byteArrayOf(
            (address and 0xFF).toByte(),
            ((address shr 8) and 0xFF).toByte(),
            ((address shr 16) and 0xFF).toByte(),
            ((address shr 24) and 0xFF).toByte()
        )
        transmit(receive = true, function = UsbaspFunction.SETLONGADDRESS, send = send, buffer = ByteArray(4), length = 4)
    }

    /**
     * usbasp_spi_paged_write(): writes [data] starting at address 0, in USBASP_WRITEBLOCKSIZE
     * (200-byte) USB chunks, framed with [pageSize] so the firmware knows page boundaries.
     * [function] is [UsbaspFunction.WRITEFLASH] or [UsbaspFunction.WRITEEEPROM] — both memories
     * use identical framing in the firmware, differing only in the function id. Only the FIRST
     * chunk is flagged; avrdude 8.2 never sets a "last block" flag despite one existing in the
     * header, so this doesn't either. Calls [onProgress] with 0..100 as it goes.
     */
    fun writeMemory(function: Int, data: ByteArray, pageSize: Int, onProgress: ((Int) -> Unit)? = null) {
        var address = 0
        var remaining = data.size
        var offset = 0
        var first = true

        while (remaining > 0) {
            val chunk = minOf(remaining, UsbaspBlockSize.WRITE)
            setLongAddress(address)

            val flags = if (first) UsbaspBlockFlag.FIRST else 0
            first = false

            val send = byteArrayOf(
                (address and 0xFF).toByte(),
                ((address shr 8) and 0xFF).toByte(),
                (pageSize and 0xFF).toByte(),
                ((flags and 0x0F) or ((pageSize and 0xF00) shr 4)).toByte()
            )

            val buffer = data.copyOfRange(offset, offset + chunk)
            val n = transmit(receive = false, function = function, send = send, buffer = buffer, length = chunk)
            if (n != chunk) throw UsbaspException("USBasp only accepted $n of $chunk bytes at address 0x${address.toString(16)}")

            offset += chunk
            address += chunk
            remaining -= chunk
            onProgress?.invoke(offset * 100 / data.size)
        }
    }

    /**
     * usbasp_spi_paged_load(): reads back [length] bytes from address 0. [function] is
     * [UsbaspFunction.READFLASH] or [UsbaspFunction.READEEPROM].
     */
    fun readMemory(function: Int, length: Int, onProgress: ((Int) -> Unit)? = null): ByteArray {
        val result = ByteArray(length)
        var address = 0
        var remaining = length
        var offset = 0

        while (remaining > 0) {
            val chunk = minOf(remaining, UsbaspBlockSize.READ)
            setLongAddress(address)

            val send = byteArrayOf((address and 0xFF).toByte(), ((address shr 8) and 0xFF).toByte(), 0, 0)
            val chunkBuffer = ByteArray(chunk)
            val n = transmit(receive = true, function = function, send = send, buffer = chunkBuffer, length = chunk)
            if (n != chunk) throw UsbaspException("USBasp only returned $n of $chunk bytes reading address 0x${address.toString(16)}")
            System.arraycopy(chunkBuffer, 0, result, offset, chunk)

            offset += chunk
            address += chunk
            remaining -= chunk
            onProgress?.invoke(offset * 100 / length)
        }
        return result
    }

    fun writeFlash(data: ByteArray, pageSize: Int, onProgress: ((Int) -> Unit)? = null) =
        writeMemory(UsbaspFunction.WRITEFLASH, data, pageSize, onProgress)

    fun readFlash(length: Int, onProgress: ((Int) -> Unit)? = null): ByteArray =
        readMemory(UsbaspFunction.READFLASH, length, onProgress)

    fun writeEeprom(data: ByteArray, pageSize: Int, onProgress: ((Int) -> Unit)? = null) =
        writeMemory(UsbaspFunction.WRITEEEPROM, data, pageSize, onProgress)

    fun readEeprom(length: Int, onProgress: ((Int) -> Unit)? = null): ByteArray =
        readMemory(UsbaspFunction.READEEPROM, length, onProgress)

    private fun transmit(receive: Boolean, function: Int, send: ByteArray, buffer: ByteArray?, length: Int): Int {
        val conn = connection ?: throw UsbaspException("USBasp connection is not open")
        return transmitRaw(conn, receive, function, send, buffer, length)
    }

    private fun transmitRaw(
        conn: UsbDeviceConnection,
        receive: Boolean,
        function: Int,
        send: ByteArray,
        buffer: ByteArray?,
        length: Int
    ): Int {
        val requestType = if (receive) 0xC0 else 0x40
        val value = ((send[1].toInt() and 0xFF) shl 8) or (send[0].toInt() and 0xFF)
        val index = ((send[3].toInt() and 0xFF) shl 8) or (send[2].toInt() and 0xFF)
        val n = conn.controlTransfer(requestType, function, value, index, buffer, length, TIMEOUT_MS)
        if (n < 0) throw UsbaspException("USB control transfer failed (function=$function)")
        return n
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        private val ZERO4 = byteArrayOf(0, 0, 0, 0)

        fun isUsbasp(device: UsbDevice): Boolean = UsbaspIds.matches(device.vendorId, device.productId)
    }
}
