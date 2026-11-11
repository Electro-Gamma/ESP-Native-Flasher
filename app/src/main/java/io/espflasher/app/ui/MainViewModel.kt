package io.espflasher.app.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.espflasher.app.ameba.AmebaImage
import io.espflasher.app.ameba.AmebaOperationManager
import io.espflasher.app.data.RecentFilesRepository
import io.espflasher.app.data.SettingsRepository
import io.espflasher.app.flashing.DeviceInfoSnapshot
import io.espflasher.app.flashing.DeviceMode
import io.espflasher.app.flashing.FirmwareCredentials
import io.espflasher.app.flashing.FlashCallback
import io.espflasher.app.flashing.FlashImage
import io.espflasher.app.flashing.FlashOperationManager
import io.espflasher.app.flashing.FlashOptions
import io.espflasher.app.flashing.ImageSlot
import io.espflasher.app.flashing.LogEntry
import io.espflasher.app.flashing.LogLevel
import io.espflasher.app.flashing.ProgressState
import io.espflasher.app.monitor.LineEnding
import io.espflasher.app.monitor.MonitorState
import io.espflasher.app.monitor.SerialMonitor
import io.espflasher.app.protocol.FlashLayout
import io.espflasher.app.protocol.FlasherException
import io.espflasher.app.protocol.PartitionEntry
import io.espflasher.app.protocol.PartitionTable
import io.espflasher.app.service.FlashForegroundService
import io.espflasher.app.sim800.Sim800Credentials
import io.espflasher.app.sim800.Sim800OperationManager
import io.espflasher.app.sim800.Sim800RomEditor
import io.espflasher.app.spiffs.SpiffsExtractedFile
import io.espflasher.app.spiffs.SpiffsImageExtractor
import io.espflasher.app.spiffs.SpiffsImageGenerator
import io.espflasher.app.spiffs.SpiffsSourceFile
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.UsbDeviceInfo
import io.espflasher.app.usb.UsbSerialManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val usbSerialManager = UsbSerialManager(application)
    private val flashManager = FlashOperationManager(application, usbSerialManager)
    private val amebaManager = AmebaOperationManager(application) { flashManager.currentPort }
    private val sim800Manager = Sim800OperationManager { flashManager.currentPort }
    private val settingsRepository = SettingsRepository(application)
    private val recentFilesRepository = RecentFilesRepository(application)

    /** Arduino-IDE-style serial monitor shown in the Monitor tab (see [SerialMonitor]). */
    private val serialMonitor = SerialMonitor(
        scope = viewModelScope,
        usbSerialManager = usbSerialManager,
        sharedPort = { flashManager.currentPort },
        log = { level, message -> appendLog(level, message) },
    )
    val monitorState: StateFlow<MonitorState> = serialMonitor.state

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var operationJob: Job? = null
    private val permissionAlreadyRequestedFor = mutableSetOf<String>()

    companion object {
        private const val MAX_LOG_LINES = 4000
        private const val BW16_UPLOAD_BAUD = 1_500_000
    }

    init {
        _uiState.update { it.copy(usbHostSupported = usbSerialManager.isUsbHostSupported()) }
        refreshDevices()
        restoreRememberedFiles()
        // Lets the notification's Stop action (see FlashForegroundService)
        // cancel whatever's running without needing its own reference to
        // this ViewModel or FlashOperationManager.
        FlashForegroundService.onStopRequested = { stop() }
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        consoleFontSizeSp = settings.consoleFontSizeSp,
                        serialConfig = it.serialConfig.copy(baudRate = it.serialConfig.baudRate.takeIf { b -> b != 115200 } ?: settings.defaultBaudRate)
                    )
                }
            }
        }
    }

    private fun restoreRememberedFiles() {
        val offsets = ImageSlot.entries.mapNotNull { slot ->
            recentFilesRepository.loadOffsetOrNull(slot)?.let { slot to "0x%X".format(it) }
        }.toMap()
        val files = mutableMapOf<ImageSlot, SelectedFile>()
        for (slot in ImageSlot.entries) {
            recentFilesRepository.load(slot)?.let { entry ->
                files[slot] = SelectedFile(entry.uri, entry.fileName, sizeBytes = 0L)
            }
        }
        // Deliberately NOT added to enabledSlots here - a file remembered from a
        // previous session should never be silently included in a flash again
        // without the person consciously re-checking it this session.
        _uiState.update { it.copy(offsetText = offsets, files = files) }
    }

    // -----------------------------------------------------------------
    // Device discovery
    // -----------------------------------------------------------------

    fun refreshDevices() {
        val devices = usbSerialManager.listDevices()
        _uiState.update { state ->
            val stillPresent = devices.any { it.androidDeviceName == state.selectedDeviceName }
            state.copy(
                availableDevices = devices,
                selectedDeviceName = if (stillPresent) state.selectedDeviceName else devices.firstOrNull()?.androidDeviceName
            )
        }
        serialMonitor.onDevicesChanged(devices.map { it.androidDeviceName })
        autoRequestPermissionForNewDevices(devices)
    }

    /**
     * As soon as a supported device shows up and this app doesn't already
     * hold permission for it, ask right away rather than waiting for an
     * explicit Connect tap - this is what makes ESP Native Flasher's own
     * "Allow ESP Native Flasher to access [device]?" prompt appear on plug-in,
     * the same way other USB-serial apps on the phone do, instead of only
     * surfacing after pressing Connect. It's a separate, app-initiated
     * system dialog from the OS's own "choose an app" chooser that fires on
     * physical attach - that earlier chooser is a different Android
     * mechanism this app has no control over (see the manifest's
     * usb_device_filter.xml), but this one is entirely within its control.
     * Only asks once per device per app session, so dismissing it doesn't
     * mean getting re-prompted on every device-list refresh.
     */
    private fun autoRequestPermissionForNewDevices(devices: List<UsbDeviceInfo>) {
        for (info in devices) {
            if (info.androidDeviceName in permissionAlreadyRequestedFor) continue
            val device = usbSerialManager.findUsbDevice(info.androidDeviceName) ?: continue
            if (usbSerialManager.hasPermission(device)) continue
            permissionAlreadyRequestedFor += info.androidDeviceName
            viewModelScope.launch {
                usbSerialManager.requestPermission(device)
                refreshDevices() // re-check hasPermission()/serial number now that the person has answered
            }
        }
    }

    fun selectDevice(device: UsbDeviceInfo) {
        _uiState.update { it.copy(selectedDeviceName = device.androidDeviceName) }
    }

    fun updateSerialConfig(config: SerialConfig) {
        _uiState.update { it.copy(serialConfig = config) }
    }

    fun updateUploadBaudRate(baud: Int) {
        _uiState.update { it.copy(uploadBaudRate = baud) }
    }

    // -----------------------------------------------------------------
    // Serial monitor (Monitor tab)
    // -----------------------------------------------------------------

    fun startMonitor() {
        val state = _uiState.value
        if (state.isBusy) {
            appendLog(LogLevel.WARNING, "Wait for the running operation to finish before starting the serial monitor.")
            return
        }
        val selected = state.availableDevices.firstOrNull { it.androidDeviceName == state.selectedDeviceName }
        if (selected == null && !flashManager.isConnected) {
            appendLog(LogLevel.ERROR, "Serial monitor: no USB device selected.")
            return
        }
        serialMonitor.start(
            device = { selected?.let { usbSerialManager.findUsbDevice(it.androidDeviceName) } },
            deviceLabel = selected?.displayLabel ?: "device",
        )
    }

    /** Returns a Job so callers that are about to use the USB device can wait until it is released. */
    fun stopMonitor(): Job = serialMonitor.stop()
    fun monitorSend(text: String): Boolean = serialMonitor.send(text)
    fun monitorResetBoard(): Boolean = serialMonitor.resetBoard()
    fun monitorSetBaud(baud: Int) = serialMonitor.setBaud(baud)
    fun monitorSetLineEnding(value: LineEnding) = serialMonitor.setLineEnding(value)
    fun monitorSetAutoscroll(value: Boolean) = serialMonitor.setAutoscroll(value)
    fun monitorSetShowTimestamp(value: Boolean) = serialMonitor.setShowTimestamp(value)
    fun monitorClear() = serialMonitor.clear()
    fun monitorText(): String = serialMonitor.asText()

    fun setDeviceMode(mode: DeviceMode) {
        _uiState.update {
            // BW16 (Realtek AmebaD) flashes at 1.5 Mbaud; picking the mode sets it (still changeable afterwards).
            it.copy(
                deviceMode = mode,
                uploadBaudRate = if (mode == DeviceMode.BW16_AMEBA && it.deviceMode != mode) BW16_UPLOAD_BAUD else it.uploadBaudRate,
            )
        }
    }

    // -----------------------------------------------------------------
    // Connection panel
    // -----------------------------------------------------------------

    fun connect() {
        val state = _uiState.value
        val deviceInfo = state.availableDevices.firstOrNull { it.androidDeviceName == state.selectedDeviceName }
        if (deviceInfo == null) {
            appendLog(LogLevel.ERROR, "No USB device selected.")
            return
        }
        val device = usbSerialManager.findUsbDevice(deviceInfo.androidDeviceName)
        if (device == null) {
            appendLog(LogLevel.ERROR, "Device is no longer attached.")
            refreshDevices()
            return
        }
        runOperation(setConnecting = true) {
            flashManager.connect(device, state.serialConfig, callback)
            _uiState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
            // Keeps the process (and any long flash/erase running in it) alive
            // if the app gets backgrounded mid-operation, with an honest
            // notification showing what's actually happening.
            FlashForegroundService.start(getApplication(), "Connected to ${deviceInfo.displayLabel} - idle")
        }
    }

    fun disconnect() {
        runOperation {
            flashManager.disconnect(callback)
            _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED, deviceInfo = null) }
            FlashForegroundService.stop(getApplication())
        }
    }

    fun resetDevice() {
        runOperation {
            when (_uiState.value.deviceMode) {
                DeviceMode.BW16_AMEBA -> amebaManager.resetDevice(callback)
                DeviceMode.SIM800 -> appendLog(
                    LogLevel.INFO,
                    "SIM800 has no software reset-to-run sequence - Flash already ends by running the new firmware, or power-cycle the module manually."
                )
                DeviceMode.ARDUINO_HEX_UPLOAD -> appendLog(
                    LogLevel.INFO,
                    "Arduino Hex Upload runs in its own screen - use \"Open Arduino Hex Upload\" below instead of Reset."
                )
                DeviceMode.AUTO_ESP -> flashManager.resetDevice(callback)
            }
        }
    }

    fun enterBootloader() {
        runOperation {
            when (_uiState.value.deviceMode) {
                DeviceMode.BW16_AMEBA -> amebaManager.enterDownloadMode(callback)
                DeviceMode.SIM800 -> appendLog(
                    LogLevel.INFO,
                    "SIM800 has no software download-mode trigger - Flash itself waits for you to power on or reset the module."
                )
                DeviceMode.ARDUINO_HEX_UPLOAD -> appendLog(
                    LogLevel.INFO,
                    "Arduino Hex Upload runs in its own screen - use \"Open Arduino Hex Upload\" below instead of Bootloader."
                )
                DeviceMode.AUTO_ESP -> flashManager.enterBootloaderManual(callback)
            }
        }
    }

    // -----------------------------------------------------------------
    // File selection
    // -----------------------------------------------------------------

    fun fileSelected(slot: ImageSlot, uri: Uri, fileName: String, sizeBytes: Long) {
        val offset = recentFilesRepository.loadOffsetOrNull(slot) ?: slot.defaultOffset ?: 0L
        recentFilesRepository.save(slot, uri, fileName, offset)
        _uiState.update { state ->
            state.copy(
                files = state.files + (slot to SelectedFile(uri, fileName, sizeBytes)),
                offsetText = state.offsetText + (slot to "0x%X".format(offset)),
                // A file picked this session is what the person clearly wants
                // flashed - auto-include it rather than making them find a
                // second checkbox right after they just picked the file.
                enabledSlots = state.enabledSlots + slot
            )
        }
        // Piggyback on picking the Partition Table file (which the person
        // already has to do to flash it) to also locate SPIFFS automatically -
        // no separate "load partition table" step needed.
        if (slot == ImageSlot.PARTITION_TABLE) {
            detectSpiffsFromPartitionTable(uri)
        }
    }

    /**
     * Parses a Partition Table file locally - no device connection needed,
     * see [PartitionTable] - and, if it lists a SPIFFS partition, fills in
     * the SPIFFS slot's offset automatically instead of leaving it for the
     * person to look up and type in by hand.
     */
    private fun detectSpiffsFromPartitionTable(uri: Uri) {
        viewModelScope.launch {
            val bytes = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: run {
                appendLog(LogLevel.WARNING, "Could not read the partition table file to look for SPIFFS.")
                return@launch
            }
            val table = runCatching { PartitionTable.parse(bytes) }.getOrElse {
                appendLog(LogLevel.WARNING, "Could not parse the partition table: ${it.message}")
                return@launch
            }
            applyPartitionTable(table)
        }
    }

    /**
     * Reads the partition table straight off the connected device (ESP32
     * only - see [FlashOperationManager.readPartitionTable]) and looks for
     * SPIFFS, exactly like the "Check SPIFFS"/"Detect SPIFFS" button in a
     * desktop reference tool that shells out to `parttool.py --port ...`.
     * For chips where that's not possible, picking your local
     * partition-table.bin file (handled by [detectSpiffsFromPartitionTable]
     * above, wired to the Partition Table file slot) is the only route -
     * this function will tell you that clearly instead of doing nothing.
     */
    fun detectSpiffs() {
        runOperation {
            val table = flashManager.readPartitionTable(callback)
            applyPartitionTable(table)
        }
    }

    /** Shared by both ways of getting a [PartitionTable]: parses to state, fills in the SPIFFS offset, logs what happened. */
    private fun applyPartitionTable(table: PartitionTable) {
        _uiState.update { it.copy(partitionTable = table) }
        val spiffs = table.findSpiffs()
        if (spiffs != null) {
            recentFilesRepository.saveOffset(ImageSlot.SPIFFS, spiffs.offset)
            _uiState.update { it.copy(offsetText = it.offsetText + (ImageSlot.SPIFFS to "0x%X".format(spiffs.offset))) }
            appendLog(
                LogLevel.SUCCESS,
                "Found SPIFFS partition \"${spiffs.label}\" at 0x%X (%s) - filled in the SPIFFS offset."
                    .format(spiffs.offset, FlashLayout.humanReadableSize(spiffs.size))
            )
        } else {
            val found = table.entries.joinToString(", ") { "${it.label} (${it.subtypeName})" }
            appendLog(
                LogLevel.WARNING,
                "No SPIFFS partition in this table" + if (found.isNotEmpty()) " - found: $found" else " (table is empty)"
            )
        }
    }

    /**
     * Erases only the SPIFFS partition's region rather than the whole chip -
     * needs [MainUiState.partitionTable] to already know where it is (i.e.
     * the Partition Table file must have been picked at least once this
     * session, or a previous session's offset restored it).
     */
    fun eraseSpiffsOnly() {
        val spiffs = _uiState.value.partitionTable?.findSpiffs()
        if (spiffs == null) {
            appendLog(LogLevel.WARNING, "No SPIFFS partition known yet - pick your Partition Table file first so its offset and size can be read.")
            return
        }
        runOperation {
            flashManager.erasePartition(
                spiffs.offset,
                spiffs.size,
                "SPIFFS (${spiffs.label})",
                resetAfter = _uiState.value.flashOptions.resetAfterFlashing,
                callback = callback
            )
        }
    }

    /**
     * Builds a SPIFFS image from every file under a picked folder and
     * selects the result as this session's SPIFFS file, exactly as if it
     * had been picked by hand - so it flows through the normal flashing
     * pipeline with no other changes needed.
     *
     * Uses [MainUiState.partitionTable]'s SPIFFS entry for the image size
     * when known (so it comes out exactly partition-sized); otherwise
     * [fallbackSizeBytes] is used, which the caller must have gotten from
     * the person some other way (there's no way to guess a safe size).
     *
     * See [SpiffsImageGenerator]'s doc comment before trusting the result
     * on real hardware - it hasn't been validated against ESP-IDF's own
     * spiffsgen.py, only reasoned through from the public format.
     */
    fun generateSpiffsImage(treeUri: Uri, fallbackSizeBytes: Long) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null || !root.isDirectory) {
                appendLog(LogLevel.WARNING, "Couldn't open that folder.")
                return@launch
            }

            val sourceFiles = mutableListOf<SpiffsSourceFile>()
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory) {
                        walk(child, "$prefix$name/")
                    } else {
                        val bytes = runCatching {
                            context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes != null) sourceFiles += SpiffsSourceFile("$prefix$name", bytes)
                    }
                }
            }
            walk(root, "")

            if (sourceFiles.isEmpty()) {
                appendLog(LogLevel.WARNING, "That folder has no files to embed.")
                return@launch
            }

            val size = _uiState.value.partitionTable?.findSpiffs()?.size ?: fallbackSizeBytes
            if (size <= 0) {
                appendLog(LogLevel.WARNING, "Don't know the SPIFFS partition size yet - pick your Partition Table file first.")
                return@launch
            }

            val image = try {
                SpiffsImageGenerator(imageSize = size).generate(sourceFiles)
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "Couldn't build the SPIFFS image: ${e.message}")
                return@launch
            }

            val outFile = File(context.cacheDir, "spiffs_generated.bin")
            outFile.writeBytes(image)
            fileSelected(ImageSlot.SPIFFS, Uri.fromFile(outFile), "spiffs_generated.bin", image.size.toLong())
            appendLog(
                LogLevel.SUCCESS,
                "Built a ${FlashLayout.humanReadableSize(image.size.toLong())} SPIFFS image from ${sourceFiles.size} file(s) - selected it as the SPIFFS file."
            )
        }
    }

    /**
     * Reads the live SPIFFS partition off the connected device and unpacks
     * every file it contains into a `SPIFFS_DATA` folder created inside
     * [destTreeUri] - the "GET" button's action. Needs [MainUiState.partitionTable]
     * to already know where SPIFFS is (same precondition as [eraseSpiffsOnly]).
     * See [SpiffsImageExtractor]'s doc comment for why the format understanding
     * behind this is trustworthy - it's the same one [SpiffsImageGenerator]
     * writes, confirmed against a real spiffsgen.py and proven on hardware.
     */
    fun extractSpiffs(destTreeUri: Uri) {
        val spiffs = _uiState.value.partitionTable?.findSpiffs()
        if (spiffs == null) {
            appendLog(LogLevel.WARNING, "No SPIFFS partition known yet - pick your Partition Table file first so its offset and size can be read.")
            return
        }
        runOperation {
            val bytes = flashManager.readFlashBytes(spiffs.offset, spiffs.size, _uiState.value.uploadBaudRate, "Reading SPIFFS", callback)
            val files = try {
                SpiffsImageExtractor().extract(bytes)
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "Couldn't parse the SPIFFS image: ${e.message}")
                return@runOperation
            }
            if (files.isEmpty()) {
                appendLog(LogLevel.WARNING, "No files found in the SPIFFS partition.")
                return@runOperation
            }

            val context = getApplication<Application>()
            val root = DocumentFile.fromTreeUri(context, destTreeUri)
            if (root == null || !root.isDirectory) {
                appendLog(LogLevel.WARNING, "Couldn't open that folder.")
                return@runOperation
            }
            val dataDir = root.findFile("SPIFFS_DATA")?.takeIf { it.isDirectory } ?: root.createDirectory("SPIFFS_DATA")
            if (dataDir == null) {
                appendLog(LogLevel.WARNING, "Couldn't create SPIFFS_DATA in that folder.")
                return@runOperation
            }

            var written = 0
            for (file in files) {
                if (writeExtractedFile(context, dataDir, file)) written++
            }
            appendLog(
                LogLevel.SUCCESS,
                "Extracted $written of ${files.size} file(s) from SPIFFS (${FlashLayout.humanReadableSize(spiffs.size)}) to SPIFFS_DATA."
            )
        }
    }

    /** Recreates [file]'s path (SPIFFS names are flat, "/" is just a character in them) as real nested folders under [root]. */
    private fun writeExtractedFile(context: android.content.Context, root: DocumentFile, file: SpiffsExtractedFile): Boolean {
        val segments = file.path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        var dir = root
        for (seg in segments.dropLast(1)) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg) ?: return false
        }
        val fileName = segments.last()
        dir.findFile(fileName)?.delete() // allow re-extraction to overwrite cleanly
        val doc = dir.createFile("application/octet-stream", fileName) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(file.content) } != null
        }.getOrDefault(false)
    }

    fun clearFile(slot: ImageSlot) {
        recentFilesRepository.clear(slot)
        _uiState.update { it.copy(files = it.files - slot, enabledSlots = it.enabledSlots - slot) }
    }

    /** The checkbox next to each slot - whether it's actually included in the next Flash/Verify. */
    fun setSlotEnabled(slot: ImageSlot, enabled: Boolean) {
        _uiState.update {
            it.copy(enabledSlots = if (enabled) it.enabledSlots + slot else it.enabledSlots - slot)
        }
    }

    fun offsetTextChanged(slot: ImageSlot, text: String) {
        _uiState.update { it.copy(offsetText = it.offsetText + (slot to text)) }
        parseAddress(text)?.let { recentFilesRepository.saveOffset(slot, it) }
    }

    // -----------------------------------------------------------------
    // Additional files ("+" button - unlimited extra entries beyond the six named slots)
    // -----------------------------------------------------------------

    fun addCustomEntry(uri: Uri, fileName: String) {
        val entry = CustomFileEntry(
            id = java.util.UUID.randomUUID().toString(),
            uri = uri,
            fileName = fileName,
            offsetText = "0x0",
            enabled = true
        )
        _uiState.update { it.copy(customEntries = it.customEntries + entry) }
    }

    fun removeCustomEntry(id: String) {
        _uiState.update { it.copy(customEntries = it.customEntries.filterNot { e -> e.id == id }) }
    }

    fun customEntryOffsetChanged(id: String, text: String) {
        _uiState.update { state ->
            state.copy(customEntries = state.customEntries.map { if (it.id == id) it.copy(offsetText = text) else it })
        }
    }

    fun setCustomEntryEnabled(id: String, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(customEntries = state.customEntries.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    // -----------------------------------------------------------------
    // Flash options
    // -----------------------------------------------------------------

    fun updateFlashOptions(options: FlashOptions) {
        _uiState.update { it.copy(flashOptions = options) }
    }

    fun updateFirmwareCredentials(credentials: FirmwareCredentials) {
        _uiState.update { it.copy(firmwareCredentials = credentials) }
    }

    fun updateSim800Credentials(credentials: Sim800Credentials) {
        _uiState.update { it.copy(sim800Credentials = credentials) }
    }

    // -----------------------------------------------------------------
    // Bottom action bar
    // -----------------------------------------------------------------

    fun detect() {
        runOperation {
            val info = flashManager.detectDevice(callback)
            applyChipDefaults(info)
            _uiState.update { it.copy(deviceInfo = info) }
        }
    }

    private fun applyChipDefaults(info: DeviceInfoSnapshot) {
        val bootloaderOffset = "0x%X".format(info.chip.bootloaderOffset)
        val flashSize = info.flashSizeBytes
        _uiState.update { state ->
            state.copy(
                offsetText = state.offsetText + (ImageSlot.BOOTLOADER to bootloaderOffset),
                // Auto-append the Read Flash to File offset/length from the
                // capacity Detect actually read off the chip, so "Dump Flash
                // to File" defaults to the whole chip without the person
                // needing to know or type its size. Left untouched if this
                // chip didn't report a usable flash ID.
                advancedReadOffsetText = if (flashSize != null) "0x0" else state.advancedReadOffsetText,
                advancedReadLengthText = flashSize?.let { FlashLayout.formatHex(it) } ?: state.advancedReadLengthText
            )
        }
    }

    fun erase() {
        val state = _uiState.value
        if (state.deviceMode == DeviceMode.BW16_AMEBA) {
            runOperation {
                val images = buildAmebaImageList(_uiState.value)
                if (images.isEmpty()) {
                    appendLog(LogLevel.WARNING, "No BW16 files enabled - pick at least one and make sure its checkbox is checked before erasing.")
                    return@runOperation
                }
                amebaManager.eraseFlash(images, resetAfter = _uiState.value.flashOptions.resetAfterFlashing, callback = callback)
            }
            return
        }
        if (state.deviceMode == DeviceMode.SIM800) {
            appendLog(
                LogLevel.INFO,
                "SIM800 has no separate erase step - check \"Format FAT before writing\" next to the ROM file, then Flash."
            )
            return
        }
        if (state.deviceMode == DeviceMode.ARDUINO_HEX_UPLOAD) {
            appendLog(LogLevel.INFO, "Arduino Hex Upload runs in its own screen - use \"Open Arduino Hex Upload\" below.")
            return
        }
        runOperation { flashManager.eraseFlash(resetAfter = _uiState.value.flashOptions.resetAfterFlashing, callback = callback) }
    }

    /**
     * The "App + Partition Only" option offered alongside a full chip erase
     * (ESP32-series chips only - see [io.espflasher.app.protocol.ChipType.isEsp32Series]
     * and [MainActivity][io.espflasher.app.ui.MainActivity]'s erase confirmation
     * dialog). Wipes the partition-table sector plus every app-type entry it
     * lists (factory, ota_0, ota_1, ...), leaving NVS, PHY calibration, and
     * SPIFFS/LittleFS untouched - same idea as [eraseSpiffsOnly], just for the
     * app side instead. Needs a partition table already known, exactly like
     * that function.
     */
    fun eraseAppAndPartitionTable() {
        val state = _uiState.value
        val chip = state.deviceInfo?.chip
        if (chip == null || !chip.isEsp32Series) {
            appendLog(LogLevel.WARNING, "App + Partition erase is only available for ESP32-series chips - run Detect first.")
            return
        }
        val table = state.partitionTable
        if (table == null) {
            appendLog(LogLevel.WARNING, "No partition table known yet - pick your Partition Table file first so the app partitions' offsets/sizes can be read.")
            return
        }
        val appEntries = table.entries.filter { it.type == PartitionEntry.TYPE_APP }
        if (appEntries.isEmpty()) {
            appendLog(LogLevel.WARNING, "The partition table has no app partitions to erase.")
            return
        }
        runOperation {
            flashManager.erasePartition(
                chip.partitionTableOffset, FlashLayout.SECTOR_SIZE.toLong(), "Partition Table",
                resetAfter = false, callback = callback
            )
            for (entry in appEntries) {
                flashManager.erasePartition(entry.offset, entry.size, "app (${entry.label})", resetAfter = false, callback = callback)
            }
            if (_uiState.value.flashOptions.resetAfterFlashing) flashManager.resetDevice(callback)
        }
    }

    fun flash() {
        val state = _uiState.value
        if (state.deviceMode == DeviceMode.BW16_AMEBA) {
            runOperation {
                val images = buildAmebaImageList(_uiState.value)
                if (images.isEmpty()) {
                    appendLog(LogLevel.WARNING, "No BW16 files enabled - pick at least one and make sure its checkbox is checked before flashing.")
                    return@runOperation
                }
                amebaManager.flashImages(
                    images,
                    _uiState.value.uploadBaudRate,
                    resetAfter = _uiState.value.flashOptions.resetAfterFlashing,
                    callback = callback
                )
            }
            return
        }
        if (state.deviceMode == DeviceMode.SIM800) {
            val file = state.files[ImageSlot.SIM800_ROM]
            if (file == null) {
                appendLog(LogLevel.WARNING, "No SIM800 ROM file selected - pick one first.")
                return
            }
            runOperation {
                val rom = runCatching {
                    getApplication<Application>().contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                }.getOrNull()
                if (rom == null) {
                    appendLog(LogLevel.ERROR, "Couldn't read ${file.fileName}.")
                    return@runOperation
                }
                sim800Manager.flashRom(rom, formatFat = _uiState.value.sim800FormatFat, callback = callback)
            }
            return
        }
        if (state.deviceMode == DeviceMode.ARDUINO_HEX_UPLOAD) {
            appendLog(LogLevel.INFO, "Arduino Hex Upload runs in its own screen - use \"Open Arduino Hex Upload\" below.")
            return
        }
        val images = buildImageList(state)
        if (images.isEmpty()) {
            appendLog(LogLevel.WARNING, "No files enabled - pick a file and make sure its checkbox is checked before flashing.")
            return
        }
        runOperation { flashManager.writeImages(images, state.flashOptions, state.uploadBaudRate, callback, state.firmwareCredentials) }
    }

    fun verify() {
        val state = _uiState.value
        val images = buildImageList(state)
        if (images.isEmpty()) {
            appendLog(LogLevel.WARNING, "No files enabled - pick a file and make sure its checkbox is checked before verifying.")
            return
        }
        runOperation { flashManager.verifyImages(images, callback, state.firmwareCredentials) }
    }

    fun readFlashToFile(destUri: Uri) {
        val state = _uiState.value
        val offset = parseAddress(state.advancedReadOffsetText)
        val length = parseAddress(state.advancedReadLengthText)
        if (offset == null || length == null) {
            appendLog(LogLevel.ERROR, "Invalid read offset/length.")
            return
        }
        runOperation { flashManager.readFlashToFile(offset, length, destUri, state.uploadBaudRate, callback) }
    }

    fun stop() {
        operationJob?.cancel()
        appendLog(LogLevel.WARNING, "Stop requested.")
        _uiState.update { it.copy(isBusy = false, progress = ProgressState.IDLE) }
    }

    private fun buildImageList(state: MainUiState): List<FlashImage> {
        val namedImages = state.files.mapNotNull { (slot, file) ->
            if (slot !in state.enabledSlots) return@mapNotNull null
            val offsetStr = state.offsetText[slot] ?: return@mapNotNull null
            val offset = parseAddress(offsetStr) ?: return@mapNotNull null
            FlashImage(slot.label, file.uri, file.fileName, offset, file.sizeBytes, slot.requiresImageMagic)
        }
        val customImages = state.customEntries.mapNotNull { entry ->
            if (!entry.enabled) return@mapNotNull null
            val offset = parseAddress(entry.offsetText) ?: return@mapNotNull null
            FlashImage(entry.fileName, entry.uri, entry.fileName, offset, 0L, requiresImageMagic = false)
        }
        return namedImages + customImages
    }

    /**
     * Unlike [buildImageList] (which just carries a [Uri] for the ESP write
     * path to stream lazily block-by-block), [AmebaImage] needs the actual
     * bytes up front - the Ameba protocol computes a whole-file checksum for
     * verification (see [io.espflasher.app.ameba.AmebaProtocol.fileChecksum])
     * that has to be known before writing starts, not derivable from a
     * stream. These 3 BW16 files are firmware-sized (tens to a couple
     * hundred KB), not full flash dumps, so reading them fully into memory
     * here is fine.
     */
    private suspend fun buildAmebaImageList(state: MainUiState): List<AmebaImage> {
        val bw16Slots = listOf(ImageSlot.BW16_KM0_BOOT, ImageSlot.BW16_KM4_BOOT, ImageSlot.BW16_IMAGE2)
        val context = getApplication<Application>()
        val images = mutableListOf<AmebaImage>()
        for (slot in bw16Slots) {
            if (slot !in state.enabledSlots) continue
            val file = state.files[slot] ?: continue
            val offset = state.offsetText[slot]?.let { parseAddress(it) } ?: slot.defaultOffset ?: continue
            val bytes = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                appendLog(LogLevel.WARNING, "Couldn't read ${file.fileName} for ${slot.label} - skipping it.")
                continue
            }
            images += AmebaImage(slot.label, offset, bytes)
        }
        return images
    }

    // -----------------------------------------------------------------
    // SIM800 panel - see io.espflasher.app.sim800
    // -----------------------------------------------------------------

    fun setSim800FormatFat(value: Boolean) = _uiState.update { it.copy(sim800FormatFat = value) }

    /**
     * The "Rebrand ROM..." button next to the SIM800 ROM slot - ports
     * `edit_firmware_name.py`. Reads whatever file is currently picked for
     * [ImageSlot.SIM800_ROM], applies [Sim800RomEditor] using the person's
     * current [MainUiState.sim800Credentials] values (editable in the
     * Credentials panel when Device mode is SIM800 - defaults to exactly
     * what the reference script hardcoded) in memory, then writes the
     * result to [destUri] (a location the person just chose via a Save-As
     * picker) - the originally-picked file itself is never modified.
     */
    fun rebrandSim800Rom(destUri: Uri) {
        val file = _uiState.value.files[ImageSlot.SIM800_ROM]
        if (file == null) {
            appendLog(LogLevel.WARNING, "No SIM800 ROM file selected - pick one first.")
            return
        }
        runOperation {
            val context = getApplication<Application>()
            val original = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (original == null) {
                appendLog(LogLevel.ERROR, "Couldn't read ${file.fileName}.")
                return@runOperation
            }
            val result = Sim800RomEditor.applyReplacements(original, _uiState.value.sim800Credentials.toReplacements())
            val written = runCatching {
                context.contentResolver.openOutputStream(destUri)?.use { it.write(result.rom) }
            }.isSuccess
            if (!written) {
                appendLog(LogLevel.ERROR, "Couldn't save the rebranded ROM.")
                return@runOperation
            }
            for ((token, count) in result.replacementCounts) {
                appendLog(LogLevel.INFO, "\"$token\": $count replacement(s)")
            }
            appendLog(LogLevel.SUCCESS, "Rebrand complete - saved.")
        }
    }

    /**
     * The "Change IMEI..." button next to the SIM800 ROM slot - ports
     * `sim800_change_imei.py`. Runs entirely over AT commands on whatever
     * port is already connected (the module's normal running firmware, not
     * its boot ROM - see [io.espflasher.app.sim800.Sim800OperationManager]'s
     * doc comment), so unlike [flash] this needs a plain Connect first, not
     * a power-on/reset into download mode.
     */
    fun changeSim800Imei(tac: String) {
        runOperation { sim800Manager.changeImei(tac, callback) }
    }

    // -----------------------------------------------------------------
    // Advanced panel
    // -----------------------------------------------------------------

    fun advancedReadOffsetChanged(text: String) = _uiState.update { it.copy(advancedReadOffsetText = text) }
    fun advancedReadLengthChanged(text: String) = _uiState.update { it.copy(advancedReadLengthText = text) }

    /**
     * Called when the person taps a "Quick size" preset next to the read
     * Length field. Always appends the picked size into Length; also resets
     * Offset to 0x0 if it wasn't already a valid address, since a preset is
     * meant to mean "read this much from the start" unless they've deliberately
     * set a different starting point.
     */
    fun applyReadSizePreset(bytes: Long) {
        _uiState.update { state ->
            val offset = if (FlashLayout.parseAddress(state.advancedReadOffsetText) == null) "0x0" else state.advancedReadOffsetText
            state.copy(advancedReadOffsetText = offset, advancedReadLengthText = FlashLayout.formatHex(bytes))
        }
    }

    // -----------------------------------------------------------------
    // Console
    // -----------------------------------------------------------------

    fun clearLog() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun saveLog(destUri: Uri) {
        viewModelScope.launch {
            val text = _uiState.value.logs.joinToString("\n") { formatLogLine(it) }
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destUri)?.use {
                    it.write(text.toByteArray())
                }
            }.onSuccess {
                appendLog(LogLevel.SUCCESS, "Log saved.")
            }.onFailure {
                appendLog(LogLevel.ERROR, "Could not save log: ${it.message}")
            }
        }
    }

    fun logAsText(): String = _uiState.value.logs.joinToString("\n") { formatLogLine(it) }

    private fun formatLogLine(entry: LogEntry): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(entry.timestampMs))
        return "[$time] [${entry.level}] ${entry.message}"
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private var lastNotificationUpdateMs = 0L

    private val callback = object : FlashCallback {
        override fun onLog(level: LogLevel, message: String) = appendLog(level, message)
        override fun onProgress(progress: ProgressState) {
            _uiState.update { it.copy(progress = progress) }
            if (progress.isRunning) {
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateMs > 500) {
                    lastNotificationUpdateMs = now
                    val status = if (progress.bytesTotal > 0) {
                        "${progress.operationLabel} - ${progress.percent}%"
                    } else {
                        progress.operationLabel
                    }
                    FlashForegroundService.updateStatus(getApplication(), status)
                }
            }
        }
    }

    private fun appendLog(level: LogLevel, message: String) {
        _uiState.update { state ->
            val updated = (state.logs + LogEntry(level, message))
            state.copy(logs = if (updated.size > MAX_LOG_LINES) updated.takeLast(MAX_LOG_LINES) else updated)
        }
    }

    private fun runOperation(setConnecting: Boolean = false, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) {
            appendLog(LogLevel.WARNING, "Another operation is already running.")
            return
        }
        // Every chip-facing entry point funnels through here (including
        // ensureReady()'s own implicit detectDevice() call when Detect
        // wasn't pressed explicitly first), so this is the one place that
        // needs to stay in sync with the checkbox rather than each caller
        // remembering to set it.
        flashManager.useStubLoader = _uiState.value.flashOptions.useStubLoader
        operationJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastError = null, connectionState = if (setConnecting) ConnectionState.CONNECTING else it.connectionState) }
            try {
                // The serial monitor reads from the same USB port, so release it first.
                serialMonitor.stopAndJoin()
                block()
            } catch (e: FlasherException) {
                appendLog(LogLevel.ERROR, e.message ?: "Unknown error")
                _uiState.update { it.copy(lastError = e.message) }
                if (setConnecting) _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                appendLog(LogLevel.WARNING, "Operation cancelled.")
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "Unexpected error: ${e.message}")
                _uiState.update { it.copy(lastError = e.message) }
            } finally {
                _uiState.update { it.copy(isBusy = false, progress = ProgressState.IDLE) }
                if (_uiState.value.isConnected) {
                    val label = flashManager.connectedDeviceInfo?.displayLabel ?: "device"
                    FlashForegroundService.updateStatus(getApplication(), "Connected to $label - idle")
                }
            }
        }
    }

    private fun parseAddress(text: String): Long? = FlashLayout.parseAddress(text)

    override fun onCleared() {
        super.onCleared()
        FlashForegroundService.onStopRequested = null
        if (flashManager.isConnected) {
            viewModelScope.launch { runCatching { flashManager.disconnect(callback) } }
        }
        FlashForegroundService.stop(getApplication())
    }
}
