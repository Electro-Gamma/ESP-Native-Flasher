package io.espflasher.app.ameba

import io.espflasher.app.protocol.SerialTransport
import io.espflasher.app.util.BinaryUtils
import kotlinx.coroutines.delay

/**
 * A faithful, near line-for-line Kotlin port of a working `upload_amebad.py`
 * (Realtek AmebaD / BW16 flashing tool), not a from-scratch reimplementation.
 * Every retry loop, timing value, and - importantly - every deliberate
 * byte-overlap quirk (see [eraseCommand] and [verifyCommand]) is copied
 * exactly rather than "cleaned up", because those quirks are what the real
 * device-side flashloader actually expects; fixing them would break
 * compatibility, not improve it.
 *
 * This is a completely different wire protocol from the rest of this app -
 * a plain byte stream with single-byte sync/ack markers, not SLIP-framed -
 * which is why it lives in its own package rather than extending
 * [io.espflasher.app.protocol.EspRomLoader]. It only depends on
 * [SerialTransport], the same minimal transport interface the ESP protocol
 * layer uses, so it works over the exact same USB connection.
 */
class AmebaProtocol(private val transport: SerialTransport) {

    companion object {
        private const val SYNC = 0x15
        private const val ACK = 0x06
        const val FLASHLOADER_ADDR = 0x082000L

        /** (baud, sub-command byte) - matches the reference tool's SPEED_TABLE exactly, including the unusual 380400 entry. */
        private val SPEED_TABLE = listOf(
            1500000 to 0x18, 1444400 to 0x17, 1382400 to 0x16, 1000000 to 0x15,
            921600 to 0x14, 500000 to 0x13, 460800 to 0x12, 380400 to 0x11,
            230400 to 0x10, 153600 to 0x0F, 128000 to 0x0D, 115200 to 0x0C
        )

        private fun speedSubCommand(speed: Int): Int = SPEED_TABLE.firstOrNull { it.first == speed }?.second ?: 0x0C
    }

    // -----------------------------------------------------------------
    // DTR/RTS - this app's setDtrRts(dtr, rts) uses the same "true = asserted"
    // convention as the reference tool's pyserial ser.dtr/ser.rts, so the
    // level->signal mapping below translates directly with no polarity flip.
    // -----------------------------------------------------------------

    suspend fun enterDownloadMode() {
        transport.setDtrRts(dtr = false, rts = true) // level 0x2: EN low (reset)
        delay(500)
        transport.setDtrRts(dtr = true, rts = false) // level 0x1: TX_LOG low (download mode)
        delay(200)
        transport.setDtrRts(dtr = false, rts = false) // level 0x3: both released
        delay(500)
    }

    suspend fun resetToRun() {
        transport.setDtrRts(dtr = false, rts = true) // level 0x2
        delay(500)
        transport.setDtrRts(dtr = false, rts = false) // level 0x3
    }

    // -----------------------------------------------------------------
    // Low-level byte protocol
    // -----------------------------------------------------------------

    private suspend fun readByte(timeoutMs: Long): Int = transport.readByteOrNull(timeoutMs) ?: -1

    suspend fun flushSerial() {
        var attempts = 300
        while (attempts > 0) {
            attempts--
            val b = readByte(100)
            if (b == -1 || b == SYNC) break
        }
        transport.purgeInput()
    }

    suspend fun waitSync(count: Int): Boolean {
        var remaining = count
        var attempts = 5000
        while (remaining > 0 && attempts > 0) {
            attempts--
            val b = readByte(2000)
            if (b == -1) continue
            if (b != SYNC) {
                if (remaining == count && count > 1) {
                    continue
                } else {
                    return false
                }
            }
            remaining--
            delay(1)
        }
        return remaining <= 0
    }

    private suspend fun waitAck(): Boolean {
        var attempts = 5000
        while (attempts > 0) {
            attempts--
            val b = readByte(2000)
            when {
                b == ACK -> return true
                b == SYNC -> continue
                b != -1 -> return false
                else -> delay(1)
            }
        }
        return false
    }

    suspend fun sendCmd(data: ByteArray): Boolean {
        val cmdByte = data[0].toInt() and 0xFF
        if (cmdByte != 0x07 && !waitSync(1)) return false
        transport.write(data)
        var attempts = 5000
        while (attempts > 0) {
            attempts--
            val b = readByte(2000)
            when {
                b == ACK -> return true
                b == SYNC -> continue
                b != -1 -> return false
                else -> delay(1)
            }
        }
        return false
    }

    /** Returns the 4-byte little-endian checksum the flashloader reports, or null on failure/timeout. */
    private suspend fun verifyCmd(data: ByteArray): Long? {
        if (!waitSync(1)) return null
        transport.write(data)
        var i = 0
        val result = ByteArray(4)
        var attempts = 5000
        while (attempts > 0) {
            attempts--
            val b = readByte(2000)
            if (b == -1) {
                delay(1)
                continue
            }
            if (i == 0 && b == SYNC) continue
            if (i == 0 && b != 0x27) return null
            if (i > 0) result[i - 1] = b.toByte()
            i++
            if (i == 5) break
            delay(1)
        }
        if (i != 5) return null
        return BinaryUtils.readU32LE(result, 0)
    }

    // -----------------------------------------------------------------
    // Checksums
    // -----------------------------------------------------------------

    private fun uint8Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var s = 0xFF
        for (i in offset until offset + length) {
            s = (s + (data[i].toInt() and 0xFF)) and 0xFF
        }
        return s
    }

    fun fileChecksum(data: ByteArray): Long {
        var checksum = 0L
        val nWords = data.size / 4
        val remainder = data.size % 4
        for (i in 0 until nWords) {
            checksum = (checksum + BinaryUtils.readU32LE(data, i * 4)) and 0xFFFFFFFFL
        }
        if (remainder > 0) {
            val lastWord = BinaryUtils.readU32LE(data.copyOfRange(nWords * 4, data.size).copyOf(4), 0)
            val mask = (1L shl (remainder * 8)) - 1
            checksum = (checksum + (lastWord and mask)) and 0xFFFFFFFFL
        }
        return checksum
    }

    // -----------------------------------------------------------------
    // Block write / erase / verify commands
    // -----------------------------------------------------------------

    /** @return (success, next seq id) - seq id is threaded across every call for one session, exactly like the reference tool. */
    suspend fun writeBlock(
        addr: Long,
        data: ByteArray,
        seqIdStart: Int,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<Boolean, Int> {
        val nChunks = (data.size + 1023) / 1024
        val paddedSize = nChunks * 1024
        val padded = data.copyOf(paddedSize)
        if (paddedSize > data.size) java.util.Arrays.fill(padded, data.size, paddedSize, 0xFF.toByte())

        var seqId = seqIdStart
        val packets = ArrayList<ByteArray>(nChunks)
        for (i in 0 until nChunks) {
            val chunkAddr = addr + i.toLong() * 1024
            val pkt = ByteArray(1032)
            pkt[0] = 0x02
            pkt[1] = (seqId and 0xFF).toByte()
            pkt[2] = (seqId.inv() and 0xFF).toByte()
            BinaryUtils.u32le(chunkAddr).copyInto(pkt, 3)
            padded.copyInto(pkt, 7, i * 1024, (i + 1) * 1024)
            pkt[1031] = uint8Checksum(pkt, 0, 1031).toByte()
            packets += pkt
            seqId = (seqId + 1) and 0xFF
        }

        if (!waitSync(1)) return false to seqId

        for ((idx, pkt) in packets.withIndex()) {
            transport.write(pkt)
            if (!waitAck()) return false to seqId
            onProgress?.invoke(idx + 1, packets.size)
        }
        return true to seqId
    }

    /**
     * Deliberately replicates the reference tool's byte-overlap bug: the
     * address is packed as a 4-byte little-endian value starting at offset 1
     * (bytes 1-4), then the sector count is packed as 2 bytes starting at
     * offset 4 - directly overwriting the address's top byte - and only 6
     * bytes total are ever sent. In practice this means only the low 3 bytes
     * of the address actually reach the device; every offset this app uses
     * shares the same 0x08 top byte, which the flashloader evidently assumes.
     */
    suspend fun eraseCommand(addr: Long, size: Long): Boolean {
        val nSectors = ((size + 4095) / 4096).toInt()
        val cmd = ByteArray(6)
        cmd[0] = 0x17
        val addrBytes = BinaryUtils.u32le(addr)
        cmd[1] = addrBytes[0]
        cmd[2] = addrBytes[1]
        cmd[3] = addrBytes[2]
        cmd[4] = (nSectors and 0xFF).toByte()
        cmd[5] = ((nSectors shr 8) and 0xFF).toByte()
        return sendCmd(cmd)
    }

    /**
     * Also replicates a deliberate overlap: the (masked) address occupies
     * bytes 1-4 and the size occupies bytes 4-7, so the address's top byte is
     * overwritten by the size's low byte, and only the first 7 of the 8
     * built bytes are ever sent (the size's own top byte is silently
     * dropped). `addr and 0x8000000L.inv()` strips the fixed 0x08000000
     * flash-mapping base the write address includes, since verify wants a
     * raw flash-relative offset instead.
     */
    suspend fun verifyBlock(addr: Long, size: Int, expectedChecksum: Long): Boolean {
        val cmd = ByteArray(8)
        cmd[0] = 0x27
        BinaryUtils.u32le(addr and 0x8000000L.inv()).copyInto(cmd, 1)
        val sizeBytes = BinaryUtils.u32le(size.toLong())
        cmd[4] = sizeBytes[0]
        cmd[5] = sizeBytes[1]
        cmd[6] = sizeBytes[2]
        val result = verifyCmd(cmd.copyOf(7)) ?: return false
        return result == expectedChecksum
    }

    // -----------------------------------------------------------------
    // Speed negotiation - uses whatever baud the caller picked (this app's
    // existing Upload Baud Rate setting) rather than the reference tool's
    // own auto-probe, which repeatedly reconfigures the OS-level UART driver
    // at many rates in a row - a pattern this app's USB-serial drivers
    // weren't written to be probed with, and isn't worth the risk when the
    // person can just pick a rate the same way they already do for ESP.
    // -----------------------------------------------------------------

    suspend fun setMaxSpeed(speed: Int): Boolean {
        val subCmd = speedSubCommand(speed)
        if (speed != 115200) {
            if (!waitSync(1)) return false
            sendCmd(byteArrayOf(0x05, subCmd.toByte())) // reference tool doesn't check this call's result either
            transport.setBaudRate(speed)
        }
        if (!sendCmd(byteArrayOf(0x07))) return false
        if (!waitSync(1)) return false
        return true
    }
}

/** One image to write - [label] and [offset] normally come from [AmebaFlashMap], [data] is the file's raw bytes. */
data class AmebaImage(val label: String, val offset: Long, val data: ByteArray)

/** The 3 canonical BW16/AmebaD flash regions, straight from the reference tool's FLASH_MAP. */
object AmebaFlashMap {
    const val KM0_BOOT_OFFSET = 0x8000000L
    const val KM4_BOOT_OFFSET = 0x8004000L
    const val KM0_KM4_IMAGE2_OFFSET = 0x8006000L
}
