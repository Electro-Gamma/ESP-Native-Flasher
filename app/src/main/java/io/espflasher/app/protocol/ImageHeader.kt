package io.espflasher.app.protocol

/**
 * The 8-byte header every Espressif app/bootloader image starts with. This
 * is a publicly documented, stable on-flash format (see the "Firmware Image
 * Format" page in Espressif's ESP-IDF docs) - parsing it here is just
 * reading a binary layout, not reusing any tool's source.
 *
 * Only used for informational display and for validating that a file
 * "looks like" a real ESP image before wasting time flashing it.
 */
data class ImageHeader(
    val magic: Int,
    val segmentCount: Int,
    val flashMode: String,
    val flashFreqLabel: String,
    val flashSizeLabel: String,
    val entryPoint: Long
) {
    companion object {
        /**
         * The 4-bit flash-frequency code in byte 3 (low nibble) is NOT a
         * universal code->MHz mapping - esptool defines a different
         * `FLASH_FREQUENCY` table per chip family (one per file under
         * esptool's `targets` folder), because the code is really "which
         * SPI clock divider setting" and the same
         * divider produces a different frequency on different silicon.
         * Using one fixed table for every chip - this file's previous
         * behaviour - silently mis-labelled ESP32-C2/H2/H4/C5/C6/C61 images:
         * e.g. code 0x0 is 40 MHz on ESP32 but 30 MHz on ESP32-C2 and
         * 24 MHz on ESP32-H2.
         *
         * Special case: on ESP32-C6 (and C5/C61/S31, which share its ROM),
         * esptool's own table maps *both* "80m" and "40m" to code 0x0 -
         * literally the same byte - with the comment "workaround for wrong
         * mspi HS div value in ROM". That's a genuine encoding collision on
         * those chips, not something this app failed to resolve: the header
         * byte alone cannot distinguish the two, so 0x0 is reported as
         * "40 MHz (or 80 MHz)" there rather than picking one silently.
         */
        private val FREQ_TABLE_ESP32_STYLE = mapOf(0x0 to 40, 0x1 to 26, 0x2 to 20, 0xF to 80)
        private val FREQ_TABLE_C2 = mapOf(0x0 to 30, 0x1 to 20, 0x2 to 15, 0xF to 60)
        private val FREQ_TABLE_H2_STYLE = mapOf(0x0 to 24, 0x1 to 16, 0x2 to 12, 0xF to 48) // H2, H21, H4
        private val FREQ_TABLE_C6_STYLE = mapOf(0x2 to 20, 0xF to 80) // C6, C5, C61, S31 - 0x0 handled specially below

        private fun freqLabel(chip: ChipType?, freqCode: Int): String {
            if (chip == ChipType.ESP32_C6 || chip == ChipType.ESP32_C5 ||
                chip == ChipType.ESP32_C61 || chip == ChipType.ESP32_S31
            ) {
                if (freqCode == 0x0) return "40 MHz (or 80 MHz)"
            }
            val table = when (chip) {
                ChipType.ESP32_C2 -> FREQ_TABLE_C2
                ChipType.ESP32_H2, ChipType.ESP32_H21, ChipType.ESP32_H4 -> FREQ_TABLE_H2_STYLE
                ChipType.ESP32_C6, ChipType.ESP32_C5, ChipType.ESP32_C61, ChipType.ESP32_S31 -> FREQ_TABLE_C6_STYLE
                // ESP32, ESP8266, S2, S3, C3, P4, E22, and "chip unknown" (no device connected
                // yet) all share esptool's original table - this is the correct default, not
                // just a fallback.
                else -> FREQ_TABLE_ESP32_STYLE
            }
            return table[freqCode]?.let { "$it MHz" } ?: "Unknown (0x%X)".format(freqCode)
        }

        /**
         * @param chip the connected/target chip, if known - pass null when no
         *   device is connected yet (e.g. inspecting a file before Detect).
         *   Frequency will fall back to the ESP32/ESP8266 table in that case,
         *   same as this function always did; pass the real chip once it's
         *   known so C2/H2/H4/C5/C6/C61 images are labelled correctly.
         */
        fun parse(bytes: ByteArray, chip: ChipType? = null): ImageHeader? {
            if (bytes.size < 8) return null
            val magic = bytes[0].toInt() and 0xFF
            if (magic != EspCommand.IMAGE_MAGIC) return null
            val segmentCount = bytes[1].toInt() and 0xFF
            val flashModeCode = bytes[2].toInt() and 0xFF
            val flashSizeFreq = bytes[3].toInt() and 0xFF
            val entryPoint = (0 until 4).fold(0L) { acc, i ->
                acc or ((bytes[4 + i].toLong() and 0xFF) shl (8 * i))
            }
            val flashMode = when (flashModeCode) {
                0 -> "QIO"
                1 -> "QOUT"
                2 -> "DIO"
                3 -> "DOUT"
                else -> "Unknown (0x%02X)".format(flashModeCode)
            }
            val freqCode = flashSizeFreq and 0x0F
            val flashFreqLabel = freqLabel(chip, freqCode)
            val sizeCode = (flashSizeFreq shr 4) and 0x0F
            val sizeLabel = when (sizeCode) {
                0x0 -> "1MB"
                0x1 -> "2MB"
                0x2 -> "4MB"
                0x3 -> "8MB"
                0x4 -> "16MB"
                0x5 -> "32MB"
                0x6 -> "64MB"
                0x7 -> "128MB"
                else -> "Unknown"
            }
            return ImageHeader(magic, segmentCount, flashMode, flashFreqLabel, sizeLabel, entryPoint)
        }

        /** Cheap sanity check used before flashing: does this look like a real image? */
        fun looksValid(bytes: ByteArray): Boolean = bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == EspCommand.IMAGE_MAGIC
    }
}
