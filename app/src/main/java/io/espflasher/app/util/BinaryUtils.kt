package io.espflasher.app.util

import java.io.ByteArrayOutputStream

/** Small little-endian pack/unpack helpers - the ESP serial protocol is entirely LE. */
object BinaryUtils {

    fun u16le(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte()
    )

    fun u32le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    fun u32le(value: Int): ByteArray = u32le(value.toLong() and 0xFFFFFFFFL)

    fun readU16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU32LE(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 4) {
            result = result or ((data[offset + i].toLong() and 0xFFL) shl (8 * i))
        }
        return result
    }

    /** Concatenate several byte arrays without the fiddly [ByteArray] `+` chain. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun macString(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }
}
