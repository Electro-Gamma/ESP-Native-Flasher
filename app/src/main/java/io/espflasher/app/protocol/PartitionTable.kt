package io.espflasher.app.protocol

import io.espflasher.app.util.BinaryUtils

/**
 * A parsed ESP-IDF partition table - the 3 KB structure that normally lives
 * at flash offset 0x8000 (see [ChipType.partitionTableOffset]) and tells the
 * running app where every other partition (the app itself, OTA data, NVS,
 * SPIFFS/LittleFS, ...) lives in flash.
 *
 * This is a from-scratch reimplementation of Espressif's publicly
 * documented on-flash format (fixed 32-byte entries, magic 0xAA50,
 * terminated by an unwritten/0xFF tail or an MD5-checksum sentinel entry) -
 * not derived from any particular tool's source, same as the rest of this
 * app's protocol layer is built from the public ROM-loader protocol rather
 * than copied from esptool.
 *
 * There are two ways to get bytes to hand to [parse]:
 *  - Read the person's own locally-built `partition-table.bin` (the exact
 *    file their build already writes to [ChipType.partitionTableOffset]) -
 *    works for every chip, no device connection needed at all. This is the
 *    normal path, and the only one that works on chips other than ESP32.
 *  - On classic ESP32 only, [io.espflasher.app.flashing.FlashOperationManager]
 *    can instead read the live table back off the device with
 *    [EspRomLoader.readFlashRomOnly], since ESP32 is the one chip this app
 *    can read flash on without a stub loader (see
 *    [EspRomLoader.supportsRomOnlyRead]). Every other chip needs the
 *    local-file path above.
 */
data class PartitionTable(val entries: List<PartitionEntry>) {

    fun findByLabel(label: String): PartitionEntry? =
        entries.firstOrNull { it.label.equals(label, ignoreCase = true) }

    fun findByTypeAndSubtype(type: Int, subtype: Int): PartitionEntry? =
        entries.firstOrNull { it.type == type && it.subtype == subtype }

    /**
     * The SPIFFS partition, if there is one. Matched by the standard
     * data/spiffs type+subtype first; falls back to an entry literally
     * labelled "spiffs" for tables that store SPIFFS content under a
     * renamed or nonstandard entry.
     */
    fun findSpiffs(): PartitionEntry? =
        findByTypeAndSubtype(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_SPIFFS)
            ?: findByLabel("spiffs")

    companion object {
        private const val ENTRY_SIZE = 32
        private const val ENTRY_MAGIC = 0x50AA // on-disk bytes are 0xAA,0x50 - readU16LE(0xAA,0x50) = 0x50AA, not 0xAA50
        private const val MD5_SENTINEL_MAGIC = 0xEBEB // ESP-IDF's optional trailing checksum entry - not a real partition

        /**
         * Parses raw partition-table bytes. Stops at the first entry that
         * isn't a valid 0xAA50 partition entry - that's either the
         * all-0xFF unwritten tail every table has after its real entries,
         * or ESP-IDF's optional 0xEBEB MD5 sentinel, neither of which
         * describes a real partition.
         */
        fun parse(bytes: ByteArray): PartitionTable {
            val entries = mutableListOf<PartitionEntry>()
            var pos = 0
            while (pos + ENTRY_SIZE <= bytes.size) {
                val magic = BinaryUtils.readU16LE(bytes, pos)
                if (magic != ENTRY_MAGIC) break
                val type = bytes[pos + 2].toInt() and 0xFF
                val subtype = bytes[pos + 3].toInt() and 0xFF
                val offset = BinaryUtils.readU32LE(bytes, pos + 4)
                val size = BinaryUtils.readU32LE(bytes, pos + 8)
                val labelBytes = bytes.copyOfRange(pos + 12, pos + 28)
                val label = labelBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                val flags = BinaryUtils.readU32LE(bytes, pos + 28)
                entries += PartitionEntry(
                    type = type,
                    subtype = subtype,
                    offset = offset,
                    size = size,
                    label = label,
                    encrypted = (flags and 0x1L) != 0L
                )
                pos += ENTRY_SIZE
            }
            return PartitionTable(entries)
        }
    }
}

data class PartitionEntry(
    val type: Int,
    val subtype: Int,
    val offset: Long,
    val size: Long,
    val label: String,
    val encrypted: Boolean
) {
    companion object {
        const val TYPE_APP = 0x00
        const val TYPE_DATA = 0x01

        const val SUBTYPE_DATA_OTA = 0x00
        const val SUBTYPE_DATA_PHY = 0x01
        const val SUBTYPE_DATA_NVS = 0x02
        const val SUBTYPE_DATA_COREDUMP = 0x03
        const val SUBTYPE_DATA_NVS_KEYS = 0x04
        const val SUBTYPE_DATA_EFUSE_EM = 0x05
        const val SUBTYPE_FAT = 0x81
        const val SUBTYPE_SPIFFS = 0x82
        const val SUBTYPE_LITTLEFS = 0x83
    }

    val typeName: String
        get() = when (type) {
            TYPE_APP -> "app"
            TYPE_DATA -> "data"
            else -> "0x%02X".format(type)
        }

    val subtypeName: String
        get() = when {
            type == TYPE_DATA && subtype == SUBTYPE_DATA_OTA -> "ota"
            type == TYPE_DATA && subtype == SUBTYPE_DATA_PHY -> "phy"
            type == TYPE_DATA && subtype == SUBTYPE_DATA_NVS -> "nvs"
            type == TYPE_DATA && subtype == SUBTYPE_DATA_COREDUMP -> "coredump"
            type == TYPE_DATA && subtype == SUBTYPE_DATA_NVS_KEYS -> "nvs_keys"
            type == TYPE_DATA && subtype == SUBTYPE_DATA_EFUSE_EM -> "efuse"
            type == TYPE_DATA && subtype == SUBTYPE_FAT -> "fat"
            type == TYPE_DATA && subtype == SUBTYPE_SPIFFS -> "spiffs"
            type == TYPE_DATA && subtype == SUBTYPE_LITTLEFS -> "littlefs"
            else -> "0x%02X".format(subtype)
        }
}
