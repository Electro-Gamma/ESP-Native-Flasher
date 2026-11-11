package io.espflasher.app.sim800

/**
 * Editable values for [Sim800RomEditor]'s rebrand table - the SIM800
 * equivalent of [io.espflasher.app.flashing.FirmwareCredentials], but for a
 * completely different firmware family with its own fields. There's no
 * `$your_..._maximum_32_characters` placeholder convention here at all -
 * SIM800's own baked-in strings are patched by exact text match instead
 * (see that object), each with its own fixed byte budget (its old value's
 * length) rather than a uniform 32 bytes.
 *
 * Defaults match the exact values the person's own `edit_firmware_name.py`
 * hardcoded, so leaving every field at its default reproduces that script's
 * behavior unchanged; editing a field customizes just that one replacement.
 * Shown in the UI only when Device mode is SIM800 - see
 * [io.espflasher.app.ui.MainActivity]'s credentials panel, which shows
 * either this or the ESP32/ESP8266 fields, never both at once.
 */
data class Sim800Credentials(
    val firmwareRevision: String = "AndroidX7Z", // replaces "1418B04SIM800L24" (17 bytes)
    val productName: String = "DragonV7",        // replaces "SIMCOM_SIM800L" (14 bytes)
    val firmwareVersion: String = "V12",         // replaces "SIM800 R14.18" (13 bytes)
    val moduleName: String = "DRAX",             // replaces bare "SIM800" (6 bytes)
    val versionShort: String = "V13"             // replaces bare "R14.18" (6 bytes)
) {
    /** The same (old, new) shape [Sim800RomEditor.applyReplacements] expects, built from this credential set's current values. */
    fun toReplacements(): List<Pair<ByteArray, ByteArray>> = listOf(
        "1418B04SIM800L24".toByteArray(Charsets.US_ASCII) to firmwareRevision.toByteArray(Charsets.US_ASCII),
        "SIMCOM_SIM800L".toByteArray(Charsets.US_ASCII) to productName.toByteArray(Charsets.US_ASCII),
        "SIM800 R14.18".toByteArray(Charsets.US_ASCII) to firmwareVersion.toByteArray(Charsets.US_ASCII),
        "SIM800".toByteArray(Charsets.US_ASCII) to moduleName.toByteArray(Charsets.US_ASCII),
        "R14.18".toByteArray(Charsets.US_ASCII) to versionShort.toByteArray(Charsets.US_ASCII)
    )
}
