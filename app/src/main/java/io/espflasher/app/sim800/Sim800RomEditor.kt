package io.espflasher.app.sim800

/**
 * Ports the person's `edit_firmware_name.py`: rewrites a fixed set of
 * manufacturer/product/firmware-version strings baked into a SIM800 ROM
 * image (e.g. "SIMCOM_SIM800L" -> their own product name), byte for byte.
 *
 * Every replacement is padded with trailing 0x00 out to exactly the
 * original token's length rather than actually shortening/lengthening the
 * buffer - never shifting anything after it - because the firmware's own
 * code very likely references later data by fixed offset, and inserting or
 * deleting even one byte would silently corrupt everything past that point.
 *
 * The reference script opens `ROM_VIVA` with `r+b` and seeks/writes each
 * replacement directly into the file in place. A SAF [android.net.Uri]
 * can't be edited in place the same way a local path can, so this operates
 * on an in-memory copy instead - see
 * [io.espflasher.app.ui.MainViewModel.rebrandSim800Rom], which reads the
 * originally-picked file's bytes, calls [applyReplacements] here, and then
 * has the person choose where to save the rebranded result. The original
 * file is never touched.
 */
object Sim800RomEditor {

    /**
     * The exact old -> new string table from the person's own
     * `edit_firmware_name.py`, derived from [Sim800Credentials]'s own
     * defaults so the two never drift out of sync - see that class for the
     * editable, UI-facing shape of these same five pairs.
     */
    val DEFAULT_REPLACEMENTS: List<Pair<ByteArray, ByteArray>> = Sim800Credentials().toReplacements()

    /** [rom] is the rewritten ROM; [replacementCounts] is (old-value text, occurrences found) per table entry, for logging. */
    data class Result(val rom: ByteArray, val replacementCounts: List<Pair<String, Int>>)

    /**
     * The reference script calls `update_binary()` separately once per table
     * entry, and each call does its own fresh `open(...); content =
     * file.read()`. That means each entry's search sees every EARLIER
     * entry's replacements already committed to the file - so a later,
     * broader pattern (bare "SIM800") can no longer match inside a region
     * an earlier, more specific one ("SIM800 R14.18") already rewrote - but,
     * within one entry's own `while position != -1` loop, every occurrence
     * of THAT entry's pattern is still found by scanning the single `content`
     * snapshot read at the start of that call, never re-reading mid-loop.
     * [applyReplacements] reproduces both halves: [patched] carries forward
     * across entries (the cross-entry cascade), while each entry takes its
     * own fresh copy of it as a search snapshot before making any of its
     * own writes (the single-entry snapshot-search).
     *
     * Throws if any new value doesn't fit in its own old value's length -
     * the reference script has no such check, and a too-long value there
     * silently overwrites whatever bytes came right after the match. Now
     * that these values come from an editable [Sim800Credentials] rather
     * than a fixed table someone else already kept in budget, catching that
     * here - before it ever reaches a real device - is worth the deviation,
     * same principle [io.espflasher.app.flashing.BinaryCredentialPatcher.patch]
     * already applies for the ESP32/ESP8266 side.
     */
    fun applyReplacements(rom: ByteArray, replacements: List<Pair<ByteArray, ByteArray>> = DEFAULT_REPLACEMENTS): Result {
        val patched = rom.copyOf()
        val counts = mutableListOf<Pair<String, Int>>()

        for ((oldValue, newValue) in replacements) {
            require(newValue.size <= oldValue.size) {
                "\"${String(newValue, Charsets.US_ASCII)}\" is ${newValue.size} bytes but only ${oldValue.size} fit " +
                    "for \"${String(oldValue, Charsets.US_ASCII)}\" - shorten it."
            }
            val snapshot = patched.copyOf()
            val bytesToPad = oldValue.size - newValue.size
            val replacement = if (bytesToPad > 0) newValue + ByteArray(bytesToPad) else newValue

            var count = 0
            var position = indexOf(snapshot, oldValue, 0)
            while (position != -1) {
                replacement.copyInto(patched, position)
                count++
                position = indexOf(snapshot, oldValue, position + replacement.size)
            }
            counts += String(oldValue, Charsets.US_ASCII) to count
        }
        return Result(patched, counts)
    }

    /** Plain byte-array substring search - firmware-image-sized inputs and a handful of short tokens, so naive is plenty fast. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0) return -1
        val lastStart = haystack.size - needle.size
        outer@ for (i in from..lastStart) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
