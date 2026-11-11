package io.espflasher.app.protocol

/**
 * Per-chip constants needed to talk to each ROM bootloader: the SPI flash
 * controller register map (used to issue raw SPI NOR commands such as
 * "read JEDEC ID" through generic READ_REG/WRITE_REG), the efuse MAC
 * register, and sane default partition offsets following ESP-IDF's
 * standard `partitions.csv` layout.
 *
 * All of these are hardware facts (register addresses baked into chip
 * silicon/ROM, and Espressif's public partition-table conventions) rather
 * than expression copied from any third-party tool.
 */
enum class ChipType(
    val displayName: String,
    /** Value read back from CHIP_DETECT_MAGIC_REG_ADDR on chips that support it, else null. */
    val magicValue: Long?,
    /** chip_id reported by the GET_SECURITY_INFO command, for chips without a magic value. */
    val imageChipId: Int?,
    val bootloaderOffset: Long,
    val partitionTableOffset: Long,
    val otaDataOffset: Long,
    val appOffset: Long,
    val efuseMacRegAddr: Long,
    val spiRegBase: Long,
    val spiUsrOffset: Long,
    val spiUsr1Offset: Long,
    val spiUsr2Offset: Long,
    val spiW0Offset: Long,
    val spiMosiDlenOffset: Long?, // null on ESP8266 (older-style length encoding)
    val spiMisoDlenOffset: Long?,
    val spiAddrRegMsb: Boolean,
    val supportsSecurityInfo: Boolean,
    val supportsFlashEncryptionStatus: Boolean
) {
    ESP8266(
        displayName = "ESP8266",
        magicValue = 0xFFF0C101L,
        imageChipId = null,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x3FF00050L, // legacy scattered efuse layout, handled specially
        spiRegBase = 0x60000200L,
        spiUsrOffset = 0x1CL,
        spiUsr1Offset = 0x20L,
        spiUsr2Offset = 0x24L,
        spiW0Offset = 0x40L,
        spiMosiDlenOffset = null,
        spiMisoDlenOffset = null,
        spiAddrRegMsb = true,
        supportsSecurityInfo = false,
        supportsFlashEncryptionStatus = false
    ),
    ESP32(
        displayName = "ESP32",
        magicValue = 0x00F01D83L,
        imageChipId = 0,
        bootloaderOffset = 0x1000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x3FF5A000L, // legacy scattered efuse layout, handled specially
        spiRegBase = 0x3FF42000L,
        spiUsrOffset = 0x1CL,
        spiUsr1Offset = 0x20L,
        spiUsr2Offset = 0x24L,
        spiW0Offset = 0x80L,
        spiMosiDlenOffset = 0x28L,
        spiMisoDlenOffset = 0x2CL,
        spiAddrRegMsb = true,
        supportsSecurityInfo = false,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_S2(
        displayName = "ESP32-S2",
        magicValue = 0x000007C6L,
        imageChipId = 2,
        bootloaderOffset = 0x1000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x3F41A044L,
        spiRegBase = 0x3F402000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true, // 12-byte reply only (no chip_id)
        supportsFlashEncryptionStatus = true
    ),
    ESP32_S3(
        displayName = "ESP32-S3",
        magicValue = null,
        imageChipId = 9,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x60007044L,
        spiRegBase = 0x60002000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_C3(
        displayName = "ESP32-C3",
        magicValue = null,
        imageChipId = 5,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x60008844L,
        spiRegBase = 0x60002000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_C6(
        displayName = "ESP32-C6",
        magicValue = null,
        imageChipId = 13,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B0844L,
        spiRegBase = 0x60003000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_H2(
        displayName = "ESP32-H2",
        magicValue = null,
        imageChipId = 16,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B0844L, // H2 ROM shares the C6 register map
        spiRegBase = 0x60003000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_C2(
        displayName = "ESP32-C2",
        magicValue = null,
        imageChipId = 12,
        bootloaderOffset = 0x0000L, // inherits ESP32-C3's ROM
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x60008840L, // note: +0x040, not the usual +0x044
        spiRegBase = 0x60002000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_C5(
        displayName = "ESP32-C5",
        magicValue = null,
        imageChipId = 23,
        bootloaderOffset = 0x2000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B4844L,
        spiRegBase = 0x60003000L, // inherits ESP32-C6's ROM register map
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_C61(
        displayName = "ESP32-C61",
        magicValue = null,
        imageChipId = 20,
        bootloaderOffset = 0x0000L, // inherits ESP32-C6's ROM
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B4844L,
        spiRegBase = 0x60003000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_H21(
        displayName = "ESP32-H21",
        magicValue = null,
        imageChipId = 25,
        bootloaderOffset = 0x0000L, // inherits ESP32-H2 -> ESP32-C6's ROM
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B4044L,
        spiRegBase = 0x60003000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_H4(
        displayName = "ESP32-H4",
        magicValue = null,
        imageChipId = 28,
        bootloaderOffset = 0x2000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x600B1844L,
        spiRegBase = 0x60099000L, // own SPIMEM1 base - H4 inherits C3's ROM behaviour but NOT its SPI register base, which esptool's esp32h4.py overrides explicitly
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_P4(
        displayName = "ESP32-P4",
        magicValue = null,
        imageChipId = 18,
        bootloaderOffset = 0x2000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x5012D044L,
        spiRegBase = 0x5008D000L,
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_S31(
        displayName = "ESP32-S31",
        magicValue = null,
        imageChipId = 32,
        bootloaderOffset = 0x2000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0x20715050L, // note: +0x050, not the usual +0x044
        spiRegBase = 0x20501000L, // own SPIMEM1 base - S31 inherits C5's ROM behaviour but NOT its SPI register base, which esptool's esp32s31.py overrides explicitly
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    ),
    ESP32_E22(
        displayName = "ESP32-E22",
        magicValue = null,
        imageChipId = 31,
        bootloaderOffset = 0x0000L,
        partitionTableOffset = 0x8000L,
        otaDataOffset = 0xD000L,
        appOffset = 0x10000L,
        efuseMacRegAddr = 0xC4008044L,
        spiRegBase = 0xC3003000L, // own SPIMEM1 base, not inherited from another chip
        spiUsrOffset = 0x18L,
        spiUsr1Offset = 0x1CL,
        spiUsr2Offset = 0x20L,
        spiW0Offset = 0x58L,
        spiMosiDlenOffset = 0x24L,
        spiMisoDlenOffset = 0x28L,
        spiAddrRegMsb = false,
        supportsSecurityInfo = true,
        supportsFlashEncryptionStatus = true
    );

    /**
     * Whether this chip's ROM bootloader implements the compressed
     * FLASH_DEFL_BEGIN/DATA/END commands (on-the-fly zlib inflate during
     * flashing) on its own, with no stub loader involved.
     *
     * This mirrors esptool's own capability gate for exactly this
     * (`loader.py`'s `stub_and_esp32_function_only`, which allows
     * `flash_defl_begin` when `IS_STUB or CHIP_NAME not in ["ESP8266"]`):
     * every chip's ROM supports compressed writes except ESP8266's, which
     * predates that addition. Sending FLASH_DEFL_BEGIN to ESP8266's ROM
     * doesn't get rejected with an error - the opcode just isn't recognized,
     * so the ROM never replies at all, and the caller hangs until its own
     * timeout fires. That's a real, previously-hit failure mode (every
     * write silently timing out on an ESP8266 board), not a hypothetical
     * one - see [io.espflasher.app.flashing.FlashOperationManager.transferToFlash],
     * which is the single place this must be honored before choosing
     * compressed vs. uncompressed transfer.
     */
    val supportsCompressedWrite: Boolean get() = this != ESP8266

    /**
     * Whether this chip's ROM bootloader implements CHANGE_BAUDRATE at all.
     *
     * Same esptool gate as [supportsCompressedWrite] - `loader.py`'s
     * `change_baud` is also `@stub_and_esp32_function_only`, i.e. every chip
     * except ESP8266. Without this check, [FlashOperationManager.withFastBaud]
     * would send CHANGE_BAUDRATE to an ESP8266's ROM, get no reply (same
     * silent-ignore behavior as the unsupported compressed-write opcode),
     * and burn several real seconds waiting for a response that was never
     * coming before falling back - on every single flash/read, not just once.
     */
    val supportsBaudChange: Boolean get() = this != ESP8266

    /**
     * True for every chip in this enum except ESP8266 - i.e. the whole
     * ESP32 series (classic ESP32 through the C/S/H/P variants). Gates the
     * "App + Partition Only" erase shortcut in the UI, since it leans on
     * the ESP-IDF partition-table convention that ESP8266 firmware doesn't
     * necessarily follow the same way.
     */
    val isEsp32Series: Boolean get() = this != ESP8266

    companion object {
        /** Chips whose ROM exposes a magic value at CHIP_DETECT_MAGIC_REG_ADDR. */
        fun byMagicValue(value: Long): ChipType? = entries.firstOrNull { it.magicValue == value }

        /** Chips identified via the GET_SECURITY_INFO chip_id field instead. */
        fun byImageChipId(id: Int): ChipType? = entries.firstOrNull { it.imageChipId == id && it.magicValue == null }
    }
}
