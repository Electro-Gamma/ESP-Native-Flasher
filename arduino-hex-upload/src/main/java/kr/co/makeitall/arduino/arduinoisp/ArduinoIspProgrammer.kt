package kr.co.makeitall.arduino.arduinoisp

import android.content.Context
import kr.co.makeitall.arduino.SerialPortStreamImpl
import kr.co.makeitall.arduino.usbasp.UsbaspMcuInfo

class ArduinoIspException(message: String) : Exception(message)

/**
 * Low-level "Arduino as ISP" driver: STK500v1 relayed over serial by the well-known ArduinoISP
 * sketch (Arduino IDE: File > Examples > 11.ArduinoISP), which turns a normal Arduino board into
 * an SPI/ISP programmer for a *different*, externally-wired target chip. This is a different use
 * of STK500v1 than this app's Standard/Custom modes, which flash the connected board's own
 * bootloader — this flashes whatever chip is wired to the Arduino's MOSI/MISO/SCK/RESET pins.
 *
 * Every command here is verified against avrdude 8.2's src/stk500.c and its avrdude.conf
 * "arduino_as_isp" entry (`type = "stk500"`, `prog_modes = PM_ISP`, `baudrate = 19200`) rather
 * than assumed from general STK500v1 knowledge. Two details only came out of reading the real
 * driver code:
 *   - Chip erase and fuse/lock/calibration reads do NOT use their own dedicated STK500 command
 *     bytes (Cmnd_STK_CHIP_ERASE / READ_FUSE / READ_LOCK exist in the protocol header but
 *     avrdude's actual driver code never sends them) — they're relayed through
 *     Cmnd_STK_UNIVERSAL as raw 4-byte AVR ISP opcodes, the same ones already verified for
 *     USBasp.
 *   - For "arduino_as_isp" specifically (unlike generic stk500v1/avrisp), EEPROM addresses are
 *     word-addressed (÷2) exactly like flash, not byte-addressed — confirmed from avrdude's own
 *     set_memchr_a_div(). This only matters for classic AVR8 parts, which is all this app's MCU
 *     table contains.
 */
class ArduinoIspProgrammer(context: Context, portName: String) {

    private val serial = SerialPortStreamImpl(context, portName, BAUD_RATE)

    /** Opens the port and gives the ArduinoISP sketch time to (re-)boot after the DTR reset. */
    fun open() {
        serial.setReadTimeout(READ_TIMEOUT_MS)
        serial.setWriteTimeout(WRITE_TIMEOUT_MS)
        serial.open()
        // Opening the serial port toggles DTR on nearly every Arduino board, which resets it —
        // restarting the ArduinoISP sketch fresh. Give its setup() a moment before talking to it.
        Thread.sleep(1200)
        serial.DiscardInBuffer()
    }

    fun close() {
        try {
            leaveProgramMode()
        } catch (_: Exception) {
            // Best-effort — the port may already be in a bad state if something failed earlier.
        }
        serial.close()
    }

    // region Low-level framing

    private fun send(bytes: ByteArray) {
        serial.writeBytes(bytes, bytes.size)
    }

    private fun recv(count: Int): ByteArray {
        val buffer = ByteArray(count)
        val n = serial.readBytes(buffer, count)
        if (n != count) {
            throw ArduinoIspException(
                "Serial read timed out (got ${n.coerceAtLeast(0)} of $count bytes) — check the " +
                    "wiring and that the board is running the ArduinoISP sketch"
            )
        }
        return buffer
    }

    private fun recvByte(): Int = recv(1)[0].toInt() and 0xFF

    private fun expectInSync() {
        val b = recvByte()
        if (b == RESP_NOSYNC) throw ArduinoIspException("Lost sync with ArduinoISP — try again, or reset the board")
        if (b != RESP_INSYNC) throw ArduinoIspException("Expected INSYNC (0x14) but got 0x%02X".format(b))
    }

    private fun expectOk() {
        val b = recvByte()
        if (b != RESP_OK) {
            throw ArduinoIspException("Expected OK (0x10) but got 0x%02X — command failed or was rejected".format(b))
        }
    }

    // endregion

    /** Cmnd_STK_GET_SYNC, retried since the ArduinoISP sketch may still be booting. */
    fun getSync() {
        repeat(SYNC_RETRIES) {
            try {
                send(byteArrayOf(CMD_GET_SYNC.toByte(), SYNC_CRC_EOP.toByte()))
                expectInSync()
                expectOk()
                return
            } catch (e: Exception) {
                serial.DiscardInBuffer()
                Thread.sleep(150)
            }
        }
        throw ArduinoIspException(
            "Could not sync with ArduinoISP after $SYNC_RETRIES tries — make sure the board is " +
                "running the ArduinoISP sketch (File > Examples > 11.ArduinoISP) and is wired correctly"
        )
    }

    /** Cmnd_STK_GET_PARAMETER — used only for the informational SW-version log line. */
    fun getParameter(id: Int): Int {
        send(byteArrayOf(CMD_GET_PARAMETER.toByte(), id.toByte(), SYNC_CRC_EOP.toByte()))
        expectInSync()
        val value = recvByte()
        expectOk()
        return value
    }

    /**
     * Cmnd_STK_SET_DEVICE. Most of this 22-byte packet describes genuine STK500-hardware
     * capabilities (parallel-programming pins, target-voltage polling) that ArduinoISP's
     * software SPI relay doesn't have and doesn't act on; only the page/flash/eeprom size
     * fields carry real per-part data here, taken from [mcu].
     */
    fun setDevice(mcu: UsbaspMcuInfo) {
        val buf = ByteArray(22)
        buf[0] = CMD_SET_DEVICE.toByte()
        buf[1] = 0                          // Device code — irrelevant to a software ISP relay
        buf[2] = 0                          // Device revision
        buf[3] = 0                          // Serial+parallel support flag — unused for ISP-only
        buf[4] = 1                          // Parallel interface type — unused
        buf[5] = 1                          // Polling supported
        buf[6] = 1                          // Self-timed
        buf[7] = 1                          // Lock byte count (1 on virtually all classic AVR8)
        buf[8] = 3                          // Fuse byte count (lfuse+hfuse+efuse) — informational
        buf[9] = 0xFF.toByte()              // Flash readback poll byte 1
        buf[10] = 0xFF.toByte()             // Flash readback poll byte 2
        buf[11] = 0xFF.toByte()             // EEPROM readback poll byte 1
        buf[12] = 0xFF.toByte()             // EEPROM readback poll byte 2
        buf[13] = ((mcu.flashPageSize shr 8) and 0xFF).toByte()
        buf[14] = (mcu.flashPageSize and 0xFF).toByte()
        buf[15] = ((mcu.eepromSize shr 8) and 0xFF).toByte()
        buf[16] = (mcu.eepromSize and 0xFF).toByte()
        buf[17] = ((mcu.flashSize shr 24) and 0xFF).toByte()
        buf[18] = ((mcu.flashSize shr 16) and 0xFF).toByte()
        buf[19] = ((mcu.flashSize shr 8) and 0xFF).toByte()
        buf[20] = (mcu.flashSize and 0xFF).toByte()
        buf[21] = SYNC_CRC_EOP.toByte()

        send(buf)
        expectInSync()
        expectOk()
    }

    /** Cmnd_STK_ENTER_PROGMODE. */
    fun enterProgramMode() {
        send(byteArrayOf(CMD_ENTER_PROGMODE.toByte(), SYNC_CRC_EOP.toByte()))
        expectInSync()
        val result = recvByte()
        if (result != RESP_OK) {
            throw ArduinoIspException(
                "Target did not respond to programming enable — check wiring (MOSI/MISO/SCK/RESET/VCC/GND) and that it's powered"
            )
        }
    }

    /** Cmnd_STK_LEAVE_PROGMODE. */
    fun leaveProgramMode() {
        send(byteArrayOf(CMD_LEAVE_PROGMODE.toByte(), SYNC_CRC_EOP.toByte()))
        expectInSync()
        expectOk()
    }

    /**
     * Cmnd_STK_LOAD_ADDRESS. For "arduino_as_isp" specifically on classic AVR8 parts, BOTH
     * flash and EEPROM addresses are word-addressed (halved) — verified against avrdude's
     * set_memchr_a_div(), not assumed.
     */
    private fun loadAddress(byteAddress: Int) {
        val wordAddress = byteAddress ushr 1
        send(
            byteArrayOf(
                CMD_LOAD_ADDRESS.toByte(),
                (wordAddress and 0xFF).toByte(),
                ((wordAddress shr 8) and 0xFF).toByte(),
                SYNC_CRC_EOP.toByte()
            )
        )
        expectInSync()
        expectOk()
    }

    /** Cmnd_STK_READ_SIGN. */
    fun readSignature(): IntArray {
        send(byteArrayOf(CMD_READ_SIGN.toByte(), SYNC_CRC_EOP.toByte()))
        expectInSync()
        val sig = recv(3)
        expectOk()
        return IntArray(3) { sig[it].toInt() and 0xFF }
    }

    /** Cmnd_STK_UNIVERSAL: relays a raw 4-byte ISP command, returning its 4th echo byte. */
    fun universalCommand(cmd0: Int, cmd1: Int, cmd2: Int, cmd3: Int): Int {
        send(
            byteArrayOf(
                CMD_UNIVERSAL.toByte(), cmd0.toByte(), cmd1.toByte(), cmd2.toByte(), cmd3.toByte(),
                SYNC_CRC_EOP.toByte()
            )
        )
        expectInSync()
        val echo = recvByte()
        expectOk()
        return echo
    }

    /** Standard AVR ISP "Chip Erase" via the universal command relay (0xAC 0x80 0x00 0x00). */
    fun chipErase(chipEraseDelayMs: Long) {
        universalCommand(0xAC, 0x80, 0x00, 0x00)
        Thread.sleep(chipEraseDelayMs)
    }

    fun readLFuse(): Int = universalCommand(0x50, 0x00, 0x00, 0x00)
    fun readHFuse(): Int = universalCommand(0x58, 0x08, 0x00, 0x00)
    fun readEFuse(): Int = universalCommand(0x50, 0x08, 0x00, 0x00)
    fun readLock(): Int = universalCommand(0x58, 0x00, 0x00, 0x00)
    fun readCalibration(): Int = universalCommand(0x38, 0x00, 0x00, 0x00)

    /** Cmnd_STK_PROG_PAGE, one AVR page per call (matches avrdude's stk500_paged_write). */
    fun writeMemory(memChar: Char, data: ByteArray, pageSize: Int, onProgress: ((Int) -> Unit)? = null) {
        var address = 0
        var offset = 0
        while (offset < data.size) {
            val blockSize = minOf(pageSize, data.size - offset)
            loadAddress(address)

            val header = byteArrayOf(
                CMD_PROG_PAGE.toByte(),
                ((blockSize shr 8) and 0xFF).toByte(),
                (blockSize and 0xFF).toByte(),
                memChar.code.toByte()
            )
            send(header + data.copyOfRange(offset, offset + blockSize) + byteArrayOf(SYNC_CRC_EOP.toByte()))
            expectInSync()
            expectOk()

            offset += blockSize
            address += blockSize
            onProgress?.invoke(offset * 100 / data.size)
        }
    }

    /** Cmnd_STK_READ_PAGE, one AVR page per call (matches avrdude's stk500_paged_load). */
    fun readMemory(memChar: Char, length: Int, pageSize: Int, onProgress: ((Int) -> Unit)? = null): ByteArray {
        val result = ByteArray(length)
        var address = 0
        var offset = 0
        while (offset < length) {
            val blockSize = minOf(pageSize, length - offset)
            loadAddress(address)

            send(
                byteArrayOf(
                    CMD_READ_PAGE.toByte(),
                    ((blockSize shr 8) and 0xFF).toByte(),
                    (blockSize and 0xFF).toByte(),
                    memChar.code.toByte(),
                    SYNC_CRC_EOP.toByte()
                )
            )
            expectInSync()
            val chunk = recv(blockSize)
            expectOk()
            System.arraycopy(chunk, 0, result, offset, blockSize)

            offset += blockSize
            address += blockSize
            onProgress?.invoke(offset * 100 / length)
        }
        return result
    }

    companion object {
        private const val CMD_GET_SYNC = 0x30
        private const val CMD_GET_PARAMETER = 0x41
        private const val CMD_SET_DEVICE = 0x42
        private const val CMD_ENTER_PROGMODE = 0x50
        private const val CMD_LEAVE_PROGMODE = 0x51
        private const val CMD_LOAD_ADDRESS = 0x55
        private const val CMD_UNIVERSAL = 0x56
        private const val CMD_PROG_PAGE = 0x64
        private const val CMD_READ_PAGE = 0x74
        private const val CMD_READ_SIGN = 0x75
        private const val SYNC_CRC_EOP = 0x20

        private const val RESP_OK = 0x10
        private const val RESP_INSYNC = 0x14
        private const val RESP_NOSYNC = 0x15

        const val PARM_SW_MAJOR = 0x81
        const val PARM_SW_MINOR = 0x82

        const val BAUD_RATE = 19200 // avrdude.conf: arduino_as_isp baudrate
        private const val READ_TIMEOUT_MS = 5000
        private const val WRITE_TIMEOUT_MS = 5000
        private const val SYNC_RETRIES = 10
    }
}
