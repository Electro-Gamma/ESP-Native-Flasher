package io.espflasher.app.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Compresses image data the same way esptool does for its `FLASH_DEFL_*`
 * commands: a standard zlib stream (2-byte header + DEFLATE data + Adler-32
 * trailer) at compression level 9. The ROM bootloader's built-in decompressor
 * expects exactly this format, so `Deflater(level, nowrap = false)` - which
 * produces zlib-wrapped output, matching Python's `zlib.compress()` - is used
 * rather than raw/nowrap DEFLATE.
 */
object Compressor {
    fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}
