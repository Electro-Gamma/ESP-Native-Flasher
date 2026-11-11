package io.espflasher.app.protocol

/**
 * SPI NOR flash geometry constants shared by every supported chip (the value
 * of the SPI flash part is standardized across vendors, not chip-specific).
 */
object FlashLayout {
    const val SECTOR_SIZE = 0x1000 // 4 KiB - smallest erasable unit
    const val BLOCK_SIZE = 0x10000 // 64 KiB
    const val PAGE_SIZE = 0x100 // 256 B - smallest programmable unit

    /**
     * The erase_size field sent in FLASH_BEGIN/FLASH_DEFL_BEGIN.
     *
     * This is NOT the same as rounding [size] up to a whole number of
     * sectors starting at [offset] - that was this function's previous
     * (wrong) implementation, and it happened to go unnoticed because most
     * test writes were already sector-aligned. What each chip's ROM
     * actually wants here, per esptool:
     *  - Every chip except ESP8266's bare ROM (which is every chip in this
     *    app, since they all ultimately inherit `ESP32ROM.get_erase_size`)
     *    wants [size] passed through completely unchanged - the ROM does
     *    its own sector rounding internally.
     *  - ESP8266's bare ROM has a documented erase-size bug in FLASH_BEGIN,
     *    and needs esptool's specific compensating formula instead
     *    (`targets/esp8266.py`'s `get_erase_size`, whose own comment reads
     *    "Provides a workaround for the bootloader erase bug"). Sending
     *    the naively-correct value there is exactly what triggers status
     *    0x0106 ("message was understood, but executing it failed") on a
     *    real ESP8266 - this isn't hypothetical, it's what a real device hit.
     *
     * [isStub] gates that last case: the flasher stub is Espressif's own
     * RAM-resident program, not the buggy bare-ROM erase routine, and
     * esptool never carries the ESP8266 workaround over to it - the base
     * `ESPLoader.get_erase_size()` ("return size", loader.py) is what
     * actually runs once a stub is uploaded, ESP8266 included. Applying
     * the ROM workaround's formula there anyway silently UNDER-erases:
     * for example a 709,664-byte (0xAD420) write at offset 0 needs 174
     * sectors, but the workaround formula reports only 158 - the last
     * ~64 KB of the write region is left whatever it was before. Flash
     * can only clear bits, never set them back to 1 without a real erase,
     * so any leftover data there corrupts the tail of the new image even
     * though every block still transfers and acks normally - the write
     * reports success, and only a follow-up MD5 check catches it. This is
     * exactly the failure mode behind a "wrote OK, verify says checksum
     * mismatch" report on ESP8266 with "use stub loader" turned on.
     */
    fun eraseSizeForFlashBegin(c: ChipType, offset: Long, size: Long, isStub: Boolean): Long =
        if (c == ChipType.ESP8266 && !isStub) esp8266EraseSizeWorkaround(offset, size) else size

    private fun esp8266EraseSizeWorkaround(offset: Long, size: Long): Long {
        val sectorsPerBlock = 16L
        val sectorSize = SECTOR_SIZE.toLong()
        val numSectors = (size + sectorSize - 1) / sectorSize
        val startSector = offset / sectorSize
        var headSectors = sectorsPerBlock - (startSector % sectorsPerBlock)
        if (numSectors < headSectors) headSectors = numSectors
        return if (numSectors < 2 * headSectors) {
            (numSectors + 1) / 2 * sectorSize
        } else {
            (numSectors - headSectors) * sectorSize
        }
    }

    /** Decode a SPI flash JEDEC capacity byte (3rd byte of RDID/0x9F) to a size in bytes. */
    fun capacityByteToSize(capacityByte: Int): Long? {
        // Standard SPI NOR convention: size = 2^capacityByte bytes. Sanity-bound
        // it to 512 KB..128 MB so garbage reads don't produce nonsense values.
        if (capacityByte < 0x10 || capacityByte > 0x19) return null
        return 1L shl capacityByte
    }

    fun humanReadableSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /**
     * Parses an address/length field either as `0x`-prefixed hex or plain
     * decimal - the single source of truth for that format, shared by
     * [io.espflasher.app.ui.MainViewModel] and the Advanced panel's
     * quick-size dropdown so they agree on what counts as valid input.
     */
    fun parseAddress(text: String): Long? {
        val trimmed = text.trim()
        return try {
            if (trimmed.startsWith("0x", ignoreCase = true)) {
                trimmed.substring(2).toLong(16)
            } else {
                trimmed.toLong()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Formats a byte count the way the Offset/Length fields expect it back, e.g. `0x400000`. */
    fun formatHex(value: Long): String = "0x%X".format(value)

    /** One entry in the "Quick size" picker next to the Read Flash to File length field. */
    data class SizePreset(val label: String, val bytes: Long)

    /**
     * Common SPI NOR flash capacities, from the smallest sizes seen on
     * older ESP8266 modules up through the largest current ESP32-series
     * parts. Selecting one in the UI fills the Length field via
     * [io.espflasher.app.ui.MainViewModel.applyReadSizePreset]; Detect fills
     * it the same way from the chip's actually-reported capacity via
     * [io.espflasher.app.ui.MainViewModel.applyChipDefaults] - this list is
     * just the manual shortcut for when you want a specific size rather
     * than "the whole chip".
     */
    val READ_SIZE_PRESETS: List<SizePreset> = listOf(
        SizePreset("500 KB", 500L * 1024),
        SizePreset("1 MB", 1L * 1024 * 1024),
        SizePreset("2 MB", 2L * 1024 * 1024),
        SizePreset("4 MB", 4L * 1024 * 1024),
        SizePreset("6 MB", 6L * 1024 * 1024),
        SizePreset("8 MB", 8L * 1024 * 1024),
        SizePreset("12 MB", 12L * 1024 * 1024),
        SizePreset("16 MB", 16L * 1024 * 1024),
        SizePreset("32 MB", 32L * 1024 * 1024),
        SizePreset("64 MB", 64L * 1024 * 1024)
    )
}
