package io.espflasher.app.flashing

import android.net.Uri

/**
 * Which named slot a [FlashImage] came from - purely for UI/log labeling,
 * plus the offset each slot pre-fills with. A null [defaultOffset] is for
 * slots whose real address depends entirely on the person's own partition
 * table (SPIFFS/LittleFS especially varies a lot), so those start blank
 * rather than pre-filled with a guess that could be wrong for their board
 * and silently overwrite the wrong region.
 *
 * Beyond the ESP slots below, the file-selection panel also supports an
 * unlimited number of additional entries added via "+" (see
 * `MainUiState.customEntries`) for anything that doesn't fit a named slot.
 */
enum class ImageSlot(val label: String, val defaultOffset: Long?, val requiresImageMagic: Boolean) {
    BOOTLOADER("Bootloader", 0x1000L, requiresImageMagic = true),
    PARTITION_TABLE("Partition Table", 0x8000L, requiresImageMagic = false),
    APPLICATION("Application BIN", 0x10000L, requiresImageMagic = true),
    OTA_DATA("Boot App", 0xE000L, requiresImageMagic = false),
    SPIFFS("SPIFFS / Filesystem", null, requiresImageMagic = false),
    FIRMWARE("Firmware BIN", 0x0L, requiresImageMagic = true),

    // BW16/AmebaD (Realtek) - a completely different chip family and flashing
    // protocol (see io.espflasher.app.ameba), only shown when Device mode is
    // set to BW16. Offsets are fixed (Realtek's own flash map), unlike the
    // ESP slots above where the offset is just a starting suggestion.
    BW16_KM0_BOOT("KM0 Boot (BW16)", io.espflasher.app.ameba.AmebaFlashMap.KM0_BOOT_OFFSET, requiresImageMagic = false),
    BW16_KM4_BOOT("KM4 Boot (BW16)", io.espflasher.app.ameba.AmebaFlashMap.KM4_BOOT_OFFSET, requiresImageMagic = false),
    BW16_IMAGE2("KM0+KM4 Image2 (BW16)", io.espflasher.app.ameba.AmebaFlashMap.KM0_KM4_IMAGE2_OFFSET, requiresImageMagic = false),

    // SIM800(L) - yet another completely different protocol (see
    // io.espflasher.app.sim800), only shown when Device mode is set to
    // SIM800. There's exactly one file: the whole ROM_VIVA-style image is
    // streamed as one blob with no addressable offset at all, unlike every
    // slot above (ESP or BW16) where offset means something.
    SIM800_ROM("ROM Image (SIM800)", null, requiresImageMagic = false);

    val hasConventionalOffset: Boolean get() = defaultOffset != null
}

/**
 * Which flashing protocol the Detect/Erase/Flash/Verify/Read buttons should
 * use. [ARDUINO_HEX_UPLOAD] is the exception - it's handled entirely by
 * [io.espflasher.app.arduinohex.ArduinoHexUploadFragment], the always-on HEX
 * UPLOADER tab (see that class's own doc comment for why), so none of the
 * other device modes' file-slot/action-bar machinery applies to it at all.
 */
enum class DeviceMode(val label: String) {
    AUTO_ESP("Auto (ESP32 / ESP8266 / ...)"),
    BW16_AMEBA("BW16 (Realtek AmebaD)"),
    SIM800("SIM800 (SIMCom GSM)"),
    ARDUINO_HEX_UPLOAD("Arduino Hex Upload (AVR)")
}

data class FlashImage(
    val label: String,
    val uri: Uri,
    val fileName: String,
    val offset: Long,
    val sizeBytes: Long,
    /** Whether to warn if this file doesn't start with the ESP image magic byte (0xE9). */
    val requiresImageMagic: Boolean = false
)
