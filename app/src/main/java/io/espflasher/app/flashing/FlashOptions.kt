package io.espflasher.app.flashing

import io.espflasher.app.protocol.ChipType
import io.espflasher.app.protocol.FlashId
import io.espflasher.app.protocol.SecurityInfo

/** Mirrors the checkboxes in the Flash Options panel. */
data class FlashOptions(
    val verifyAfterWrite: Boolean = true,
    val eraseBeforeWrite: Boolean = false,
    val compressData: Boolean = true,
    val resetAfterFlashing: Boolean = true,
    val skipChecksum: Boolean = false,
    val keepUsbConnection: Boolean = false,
    /** On by default - see [io.espflasher.app.protocol.StubLoader]'s doc comment for the tradeoff. Applied on the next Detect. */
    val useStubLoader: Boolean = true
)

/**
 * Values to patch into a firmware image's `$your_..._maximum_32_characters`
 * placeholders before flashing - see [BinaryCredentialPatcher]. Collapsed
 * out of sight in the UI (like the serial settings panel) until the person
 * opens that section; a blank field here means "leave that placeholder
 * alone", not "patch it with an empty value".
 */
data class FirmwareCredentials(
    val token: String = "",
    val ssid: String = "",
    val password: String = "",
    val otaAuth: String = ""
) {
    val isEmpty: Boolean get() = token.isEmpty() && ssid.isEmpty() && password.isEmpty() && otaAuth.isEmpty()
}

/** Everything the Information page shows, gathered by [FlashOperationManager.detectDevice]. */
data class DeviceInfoSnapshot(
    val chip: ChipType,
    val revision: String,
    val macAddress: String,
    val crystalFreqMHz: Int,
    val flashId: FlashId?,
    val flashSizeBytes: Long?,
    val flashMode: String?,
    val features: List<String>,
    val securityInfo: SecurityInfo?,
    /** Whether this Detect ended with the stub flasher running - drives the "STUB" badge next to Progress. */
    val stubRunning: Boolean = false
) {
    val secureBootStatus: String get() = when {
        securityInfo == null -> "Unknown (chip does not report security info)"
        securityInfo.secureBootEnabled -> "Enabled"
        else -> "Disabled"
    }

    val flashEncryptionStatus: String get() = when {
        securityInfo == null -> "Unknown (chip does not report security info)"
        securityInfo.flashEncryptionEnabled -> "Enabled (crypt_cnt=${securityInfo.flashCryptCnt})"
        else -> "Disabled"
    }
}
