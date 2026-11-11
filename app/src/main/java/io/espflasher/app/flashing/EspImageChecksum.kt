package io.espflasher.app.flashing

import io.espflasher.app.util.BinaryUtils
import java.security.MessageDigest

/**
 * Recomputes an ESP32-family app image's own checksum byte and (if present)
 * its appended SHA-256 digest - ported from `fix_image_checksum_and_hash()`
 * in the person's `ESP_all_final_x4_FIXED.py`.
 *
 * The Arduino/ESP-IDF build tools write a checksum byte, and for most
 * targets a trailing SHA-256 hash covering the whole image, into every
 * compiled app binary. The ROM bootloader checks this on every boot and
 * refuses to run the app at all if it doesn't match. [BinaryCredentialPatcher]
 * patches placeholder bytes directly into that same image - which is
 * exactly what invalidates both of those - so [fix] has to run once,
 * immediately after every placeholder for a given file has been patched
 * (see [FlashOperationManager.readAllBytes]), or the person ends up with a
 * device that flashes successfully and then does nothing at all.
 *
 * This is the actual fix the "_FIXED" in the reference script's filename
 * refers to: an earlier version of that script patched credentials without
 * ever calling this, and silently shipped unbootable images - the same bug
 * this app's own credential patching had until now.
 */
object EspImageChecksum {

    private const val IMAGE_MAGIC = 0xE9
    private const val CHECKSUM_MAGIC = 0xEF // ESP_CHECKSUM_MAGIC - the XOR checksum's starting value
    private const val HEADER_SIZE = 24 // end of the main image header, where the first segment sub-header starts
    private const val SEGMENT_SUBHEADER_SIZE = 8 // 4-byte load address + 4-byte length, per segment
    private const val HASH_SIZE = 32 // SHA-256 digest size

    /**
     * Returns [data] with its checksum/hash fixed up, or [data] itself
     * unchanged if it doesn't start with the ESP app image magic byte
     * (0xE9) - matching the reference script's own "not a recognizable ESP
     * app image, leave it alone" early return, so this is always safe to
     * call even on a file that isn't actually an app image (a partition
     * table, a SPIFFS image, a bootloader, ...) or one that's just
     * malformed/truncated.
     *
     * ESP8266 images use a different, shorter header with no appended hash
     * at all - callers must not call this for ESP8266 binaries, only
     * ESP32-series ones (see [io.espflasher.app.protocol.ChipType.isEsp32Series]).
     */
    fun fix(data: ByteArray): ByteArray {
        if (data.isEmpty() || (data[0].toInt() and 0xFF) != IMAGE_MAGIC) return data
        if (data.size < HEADER_SIZE) return data
        val out = data.copyOf()

        val segmentCount = out[1].toInt() and 0xFF
        val hashAppended = (out[23].toInt() and 0xFF) != 0

        var pos = HEADER_SIZE
        var checksum = CHECKSUM_MAGIC
        repeat(segmentCount) {
            if (pos + SEGMENT_SUBHEADER_SIZE > out.size) return data // truncated - leave untouched rather than guess
            val segmentLength = BinaryUtils.readU32LE(out, pos + 4).toInt()
            pos += SEGMENT_SUBHEADER_SIZE
            val segmentEnd = pos + segmentLength
            if (segmentLength < 0 || segmentEnd > out.size) return data
            for (i in pos until segmentEnd) checksum = checksum xor (out[i].toInt() and 0xFF)
            pos = segmentEnd
        }

        // The checksum byte sits at the offset that pads the image out to a 16-byte boundary.
        var checksumOffset = pos
        while (checksumOffset % 16 != 15) checksumOffset++
        if (checksumOffset >= out.size) return data
        out[checksumOffset] = checksum.toByte()

        if (hashAppended) {
            val hashStart = checksumOffset + 1
            if (hashStart + HASH_SIZE > out.size) return data
            val digest = MessageDigest.getInstance("SHA-256").digest(out.copyOfRange(0, hashStart))
            digest.copyInto(out, hashStart)
        }
        return out
    }
}
