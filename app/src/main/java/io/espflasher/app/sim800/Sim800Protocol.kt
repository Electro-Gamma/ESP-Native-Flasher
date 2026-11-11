package io.espflasher.app.sim800

import io.espflasher.app.protocol.SerialTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A faithful Kotlin port of the person's `sim800flasher2.py` - a SIMCom
 * SIM800(L) module's raw ROM-download boot protocol. A third completely
 * different wire protocol in this app (not SLIP-framed like the ESP layer,
 * not the byte-stream-with-1032-byte-packets scheme [io.espflasher.app.ameba]
 * speaks either) - single sync bytes and small opcode+length+checksum
 * commands - so like Ameba it gets its own package rather than extending
 * anything in `protocol`. Only depends on [SerialTransport], the same
 * minimal interface everything else in this app talks to a port through.
 *
 * Unlike BW16/ESP, this protocol has no software-driven reset-into-bootloader
 * sequence at all - the reference script just prints "Power On/Reset Target"
 * and starts polling for a sync byte, trusting the person to physically
 * power-cycle or reset the module into its boot ROM. [sync] preserves that
 * exact expectation (see [Sim800OperationManager.flashRom]'s log message).
 *
 * One deliberate departure from the reference script: every "wait for one
 * specific response byte" loop there (`while True: ser.read(); if ... break`)
 * has no timeout at all and can only end by getting the byte it's waiting
 * for - fine for a one-shot CLI script the person can Ctrl+C, but not for an
 * app that has to stay responsive and cancellable. [sync], [sendBegin], and
 * [sendDataChunk] here are all bounded and check `ensureActive()` between
 * reads so Stop actually works if a SIM800 never answers. Everything else -
 * the opcodes, the 128-byte first chunk glued directly onto DL_BEGIN, the
 * 0x200-byte data chunks, and the plain additive (not CRC) checksum - is
 * copied exactly, since that's what the device-side bootloader expects.
 */
class Sim800Protocol(private val transport: SerialTransport) {

    companion object {
        const val SYNC_TX = 0xB5 // COMM_START_SYNC_CHAR
        const val SYNC_RX = 0x5B // COMM_430_SYNC_CHAR
        const val CMD_DL_BEGIN = 0x01
        const val CMD_DL_BEGIN_FORMAT = 0x81
        const val CMD_DL_BEGIN_RSP = 0x02
        const val CMD_DL_DATA = 0x03
        const val CMD_DL_DATA_RSP = 0x04
        const val CMD_DL_END = 0x05
        const val CMD_RUN_GSMSW = 0x07

        const val FIRST_CHUNK_SIZE = 128
        const val DATA_CHUNK_SIZE = 0x200
    }

    /**
     * Repeatedly sends [SYNC_TX] and watches for [SYNC_RX] echoing back -
     * exactly [sync_device()][sim800flasher2.py] - meant to be called right
     * after the person's been told to power on/reset the module into its
     * download-mode boot ROM, since this protocol has no software trigger
     * for that the way BW16's DTR/RTS sequence does.
     */
    suspend fun sync(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            transport.write(byteArrayOf(SYNC_TX.toByte()))
            if (transport.readByteOrNull(100) == SYNC_RX) return true
        }
        return false
    }

    /** Blocks until a byte equal to [expected] is read (bounded by [timeoutMs]) - see the class doc comment on why this differs from the reference script's unbounded wait. */
    private suspend fun waitForByte(expected: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val b = transport.readByteOrNull(200) ?: continue
            if (b == expected) return true
        }
        return false
    }

    /** Plain running sum of unsigned byte values, masked to 32 bits - matches Python's `sum(buf) & 0xFFFFFFFF` packed little-endian, not a CRC. */
    private fun additiveChecksum(data: ByteArray): Long {
        var sum = 0L
        for (b in data) sum += (b.toInt() and 0xFF)
        return sum and 0xFFFFFFFFL
    }

    private fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    /**
     * DL_BEGIN (or DL_BEGIN_FORMAT) followed immediately by the ROM's first
     * up-to-128 bytes glued onto the same command with no length/checksum
     * framing at all - the bootloader apparently expects exactly that shape
     * for this first block - then waits for DL_BEGIN_RSP.
     */
    suspend fun sendBegin(rom: ByteArray, formatFat: Boolean, timeoutMs: Long): Boolean {
        val firstChunkSize = minOf(FIRST_CHUNK_SIZE, rom.size)
        val cmd = if (formatFat) CMD_DL_BEGIN_FORMAT else CMD_DL_BEGIN
        transport.write(byteArrayOf(cmd.toByte()))
        transport.write(rom.copyOfRange(0, firstChunkSize))
        return waitForByte(CMD_DL_BEGIN_RSP, timeoutMs)
    }

    /** One DL_DATA block: opcode + 4-byte LE length + the chunk itself + a 4-byte LE additive checksum of just that chunk, then waits for DL_DATA_RSP. */
    suspend fun sendDataChunk(chunk: ByteArray, timeoutMs: Long): Boolean {
        transport.write(byteArrayOf(CMD_DL_DATA.toByte()) + le32(chunk.size.toLong()))
        transport.write(chunk)
        transport.write(le32(additiveChecksum(chunk)))
        return waitForByte(CMD_DL_DATA_RSP, timeoutMs)
    }

    /**
     * DL_END, then RUN_GSMSW to hand off execution to the freshly-written
     * ROM. The reference script reads one response byte after each of these
     * without ever checking its value - same here, just bounded.
     */
    suspend fun sendEndAndRun(timeoutMs: Long) {
        transport.write(byteArrayOf(CMD_DL_END.toByte()))
        transport.readByteOrNull(timeoutMs)
        transport.write(byteArrayOf(CMD_RUN_GSMSW.toByte()))
        transport.readByteOrNull(timeoutMs)
    }
}
