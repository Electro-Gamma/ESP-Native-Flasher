package io.espflasher.app.flashing

/**
 * Patches fixed-size `$your_..._maximum_32_characters` placeholder strings
 * inside a compiled firmware image with real values - a way to bake in a
 * WiFi SSID/password/token/OTA-auth password without recompiling from
 * source. This is a direct port of `update_binary()` from
 * `ESP_all_final_x4.py`: that script's firmware sources declare each of
 * these as a fixed 32-byte buffer and initialise it to the placeholder text
 * itself, so the placeholder's on-flash position and length are exactly
 * where the real value needs to land - find the placeholder bytes, overwrite
 * them with the real value zero-padded out to the same 32 bytes, done. No
 * relocation, no recompiling, no touching anything else in the image.
 *
 * Every placeholder is searched for independently and replaced wherever it
 * occurs (matching the Python original) - if a placeholder isn't present in
 * a given file at all, patching it is a silent no-op, which is what makes
 * it safe to run over every selected file rather than needing to know which
 * slot is "the one with credentials in it".
 */
object BinaryCredentialPatcher {

    /** One `$your_..._maximum_32_characters` placeholder and the human label for its field. */
    data class Placeholder(val bytes: ByteArray, val label: String)

    // Byte-for-byte the same four placeholder strings ESP_all_final_x4.py
    // searches for - changing these would silently stop matching real
    // firmware that still has the original placeholders compiled in.
    val TOKEN = Placeholder("\$your_tokn_maximum_32_characters".toByteArray(Charsets.US_ASCII), "token")
    val SSID = Placeholder("\$your_ssid_maximum_32_characters".toByteArray(Charsets.US_ASCII), "SSID")
    val PASSWORD = Placeholder("\$your_pswd_maximum_32_characters".toByteArray(Charsets.US_ASCII), "WiFi password")
    val OTA_AUTH = Placeholder("\$your_otap_maximum_32_characters".toByteArray(Charsets.US_ASCII), "OTA auth password")

    /** Every placeholder here is exactly this many bytes - see the class doc comment for why that matters. */
    const val MAX_VALUE_LENGTH = 32

    data class Result(val data: ByteArray, val occurrences: Int)

    /**
     * Returns [data] with every occurrence of [placeholder] replaced by
     * [value] (UTF-8), zero-padded to the placeholder's own length so nothing
     * after it shifts. [data] itself is never mutated - a fresh copy is
     * returned, so a caller than doesn't end up patching anything still gets
     * back an independent array, never the original by reference.
     *
     * Throws if [value] doesn't fit in the placeholder's own length - the
     * Python original has no such check, and a too-long value there
     * silently overwrites whatever bytes came after the placeholder in the
     * image. Catching that here, before it ever reaches a real device, is
     * strictly safer than reproducing that behaviour faithfully.
     */
    fun patch(data: ByteArray, placeholder: Placeholder, value: String): Result {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        require(valueBytes.size <= placeholder.bytes.size) {
            "${placeholder.label} is ${valueBytes.size} bytes as UTF-8, but only ${placeholder.bytes.size} bytes fit - shorten it."
        }
        val replacement = valueBytes + ByteArray(placeholder.bytes.size - valueBytes.size) // zero-padded tail
        val out = data.copyOf()
        var count = 0
        var searchFrom = 0
        while (true) {
            // Search the ORIGINAL bytes, not `out` - matches the Python
            // original's content.find(...) against its own untouched read
            // buffer. Since every search position is strictly after the
            // previous replacement's end, this never actually differs in
            // outcome from searching `out` instead; kept this way because
            // it's the more obviously-correct one to read.
            val idx = indexOf(data, placeholder.bytes, searchFrom)
            if (idx < 0) break
            replacement.copyInto(out, idx)
            count++
            searchFrom = idx + replacement.size
        }
        return Result(out, count)
    }

    /**
     * Applies every non-blank field in [credentials] to [data] in turn,
     * logging what it finds via [onResult] (label, occurrences replaced) so
     * the caller can tell the person what actually happened - "0 occurrences"
     * for a field they filled in is worth surfacing, not hiding, since it
     * usually means this particular file doesn't have that placeholder.
     */
    fun applyAll(
        data: ByteArray,
        credentials: FirmwareCredentials,
        onResult: (Placeholder, Int) -> Unit = { _, _ -> }
    ): ByteArray {
        var current = data
        for ((placeholder, value) in listOf(
            TOKEN to credentials.token,
            SSID to credentials.ssid,
            PASSWORD to credentials.password,
            OTA_AUTH to credentials.otaAuth
        )) {
            if (value.isEmpty()) continue
            val result = patch(current, placeholder, value)
            current = result.data
            onResult(placeholder, result.occurrences)
        }
        return current
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from > haystack.size - needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
