package io.espflasher.app.ui

import android.net.Uri
import io.espflasher.app.flashing.DeviceInfoSnapshot
import io.espflasher.app.flashing.DeviceMode
import io.espflasher.app.flashing.FirmwareCredentials
import io.espflasher.app.flashing.FlashOptions
import io.espflasher.app.flashing.ImageSlot
import io.espflasher.app.flashing.LogEntry
import io.espflasher.app.flashing.ProgressState
import io.espflasher.app.protocol.PartitionTable
import io.espflasher.app.sim800.Sim800Credentials
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.UsbDeviceInfo

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** A file the person picked via SAF for one of the named slots (see [ImageSlot]). */
data class SelectedFile(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
)

/**
 * One row in the "additional files" list added via the "+" button, for
 * anything that doesn't fit a named slot. Each is independently removable
 * and toggleable, same as a named slot.
 */
data class CustomFileEntry(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val offsetText: String,
    val enabled: Boolean = true
)

data class MainUiState(
    val usbHostSupported: Boolean = true,
    val availableDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDeviceName: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serialConfig: SerialConfig = SerialConfig(),
    /** The baud rate used only for the actual upload/read data transfer - selected independently of the connect/sync baud. */
    val uploadBaudRate: Int = 921600,
    /** Which flashing protocol Detect/Erase/Flash/Verify/Read use - see [DeviceMode]. */
    val deviceMode: DeviceMode = DeviceMode.AUTO_ESP,

    val files: Map<ImageSlot, SelectedFile> = emptyMap(),
    val offsetText: Map<ImageSlot, String> = emptyMap(),
    val enabledSlots: Set<ImageSlot> = emptySet(),
    val customEntries: List<CustomFileEntry> = emptyList(),

    val flashOptions: FlashOptions = FlashOptions(),
    /**
     * WiFi/token/OTA-auth values to patch into a firmware's placeholder
     * strings before flashing - see [io.espflasher.app.flashing.BinaryCredentialPatcher].
     * Kept out of the main flow behind a collapsed section, same as
     * [serialConfig]'s panel - a blank field means "leave that placeholder
     * alone", never "patch it blank".
     */
    val firmwareCredentials: FirmwareCredentials = FirmwareCredentials(),
    /**
     * Parsed from whatever file was last picked for [ImageSlot.PARTITION_TABLE]
     * (see [io.espflasher.app.ui.MainViewModel.fileSelected]) - null until
     * then. Used to auto-fill the SPIFFS slot's offset and to power "Erase
     * SPIFFS only" without needing the person to know/type the address.
     */
    val partitionTable: PartitionTable? = null,

    val progress: ProgressState = ProgressState.IDLE,
    val deviceInfo: DeviceInfoSnapshot? = null,
    val logs: List<LogEntry> = emptyList(),
    val isBusy: Boolean = false,
    val consoleFontSizeSp: Int = 12,

    val advancedReadOffsetText: String = "0x0",
    val advancedReadLengthText: String = "0x1000",

    /** The "Format FAT before writing" checkbox next to the SIM800 ROM slot - controls DL_BEGIN vs DL_BEGIN_FORMAT, see [io.espflasher.app.sim800.Sim800Protocol.sendBegin]. */
    val sim800FormatFat: Boolean = false,
    /** The "Rebrand ROM..." replacement values when Device mode is SIM800 - a separate field set from [firmwareCredentials], shown instead of it in that mode. See [io.espflasher.app.sim800.Sim800Credentials]. */
    val sim800Credentials: Sim800Credentials = Sim800Credentials(),

    val lastError: String? = null
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
}
