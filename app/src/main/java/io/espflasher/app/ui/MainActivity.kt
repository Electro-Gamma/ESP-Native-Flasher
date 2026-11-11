package io.espflasher.app.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.espflasher.app.R
import io.espflasher.app.arduinohex.ArduinoHexUploadFragment
import io.espflasher.app.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.espflasher.app.databinding.DialogMonitorSettingsBinding
import io.espflasher.app.databinding.ItemFileSlotBinding
import io.espflasher.app.flashing.FirmwareCredentials
import io.espflasher.app.flashing.FlashOptions
import io.espflasher.app.flashing.DeviceMode
import io.espflasher.app.flashing.ImageSlot
import io.espflasher.app.monitor.LineEnding
import io.espflasher.app.monitor.MonitorAdapter
import io.espflasher.app.monitor.MonitorState
import io.espflasher.app.monitor.SerialMonitor
import io.espflasher.app.protocol.FlashLayout
import io.espflasher.app.sim800.Sim800Credentials
import io.espflasher.app.usb.DataBits
import io.espflasher.app.usb.FlowControl
import io.espflasher.app.usb.Parity
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.StopBits
import io.espflasher.app.usb.UsbDeviceInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val logAdapter = LogAdapter()
    private val monitorAdapter = MonitorAdapter()
    private val slotViews = mutableMapOf<ImageSlot, ItemFileSlotBinding>()
    private val customEntryViews = mutableMapOf<String, io.espflasher.app.databinding.ItemCustomEntryBinding>()
    private var pendingSlot: ImageSlot? = null
    private var pendingCustomAdd = false
    private var suppressOffsetWatchers = false
    private var serialSettingsExpanded = false
    private var credentialsExpanded = false
    private var uploadDeviceExpanded = false
    private var flashOptionsExpanded = false

    private val espSlots = listOf(
        ImageSlot.BOOTLOADER, ImageSlot.PARTITION_TABLE, ImageSlot.APPLICATION, ImageSlot.OTA_DATA,
        ImageSlot.SPIFFS, ImageSlot.FIRMWARE
    )
    private val bw16Slots = listOf(ImageSlot.BW16_KM0_BOOT, ImageSlot.BW16_KM4_BOOT, ImageSlot.BW16_IMAGE2)
    private val sim800Slots = listOf(ImageSlot.SIM800_ROM)
    private val slotOrder = espSlots + bw16Slots + sim800Slots

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pendingSlot
        val wasCustomAdd = pendingCustomAdd
        pendingSlot = null
        pendingCustomAdd = false
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = queryFileName(uri) ?: uri.lastPathSegment.orEmpty()
        if (wasCustomAdd) {
            viewModel.addCustomEntry(uri, name)
        } else if (slot != null) {
            val size = queryFileSize(uri) ?: 0L
            viewModel.fileSelected(slot, uri, name, size)
        }
    }

    private val createDumpFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.readFlashToFile(uri)
    }

    private val createRebrandFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.rebrandSim800Rom(uri)
    }

    private val createLogFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.saveLog(uri)
    }

    private val openSpiffsFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        viewModel.generateSpiffsImage(treeUri, fallbackSizeBytes = 0L)
    }

    private val extractSpiffsFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        viewModel.extractSpiffs(treeUri)
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.refreshDevices()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way - the service still works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTabs()
        setupFileSlots()
        setupDropdowns()
        setupConsole()
        setupMonitor()
        setupListeners()
        hookHexConnectionState()
        observeState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        hexUploadFragment()?.onConnectionStateChanged = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbAttachReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        viewModel.refreshDevices()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbAttachReceiver) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // -----------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    /**
     * Wires up the persistent top TabLayout added for the four-tab redesign
     * (FLASH / FILES / HEX UPLOADER / MONITOR). This only switches which of
     * the four direct children of tabContentContainer is visible - three
     * NestedScrollViews plus hexTabContent's embedded ArduinoHexUploadFragment.
     * Every view inside them keeps its id, its place in the single
     * ActivityMainBinding (or, for the fragment, its own independent
     * ActivityArduinoHexUploadBinding), and all of its existing state/
     * listeners - toggling GONE/VISIBLE never destroys the fragment or its
     * view, so switching tabs mid-upload doesn't lose anything there either.
     * Opens on FLASH (position 0) by default, matching the tab XML order.
     */
    private fun setupTabs() {
        showTab(0)
        binding.mainTabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                showTab(tab.position)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun showTab(position: Int) {
        binding.flashTabScroll.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.filesTabScroll.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.hexTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.monitorTabContent.visibility = if (position == 3) View.VISIBLE else View.GONE
    }

    /**
     * The HEX UPLOADER tab's ArduinoHexUploadFragment, embedded via
     * hexTabContent (see activity_main.xml/setupTabs/showTab above) and
     * created once, never destroyed for the life of this Activity - so this
     * just looks up the already-running instance. Used by the FLASH tab's
     * Connect button (see setupListeners) to trigger that fragment's own
     * AVR-Programmer/USBasp connection when Device mode is
     * DeviceMode.ARDUINO_HEX_UPLOAD, now that its own "Device status" row is
     * hidden (see activity_arduino_hex_upload.xml).
     */
    private fun hexUploadFragment(): ArduinoHexUploadFragment? =
        supportFragmentManager.findFragmentById(R.id.hexTabContent) as? ArduinoHexUploadFragment

    /** Mirrors ArduinoHexUploadFragment's "programmer attached + USB permission granted" state. */
    private var hexDeviceReady = false

    /** True after the user pressed Disconnect in Hex mode; cleared by Connect or a re-plug. */
    private var hexSessionReleased = false

    private val defaultDisconnectBackground: ColorStateList? by lazy { binding.disconnectButton.backgroundTintList }
    private val defaultDisconnectTextColors: ColorStateList? by lazy { binding.disconnectButton.textColors }
    private val defaultDisconnectStroke: ColorStateList? by lazy { binding.disconnectButton.strokeColor }
    private val defaultDisconnectRipple: ColorStateList? by lazy { binding.disconnectButton.rippleColor }

    private fun hookHexConnectionState() {
        hexUploadFragment()?.onConnectionStateChanged = { ready ->
            hexDeviceReady = ready
            if (!ready) hexSessionReleased = false
            render(viewModel.uiState.value)
        }
    }

    /**
     * One place that colours Connect/Disconnect for every Device mode. Connected: Disconnect
     * takes the Flash button's colour and Connect is inactive. Not connected: Disconnect goes back to its
     * original outlined look (colours captured before the first tint) and Connect is active.
     */
    private fun renderConnectionButtons(connected: Boolean, busy: Boolean, canConnect: Boolean) {
        // Capture the untinted look first, before any tint is ever applied.
        val originalBackground = defaultDisconnectBackground
        val originalTextColors = defaultDisconnectTextColors
        val originalStroke = defaultDisconnectStroke
        val originalRipple = defaultDisconnectRipple
        // Connected look = exactly the Flash button's look (filled theme-primary button).
        val primary = MaterialColors.getColor(binding.disconnectButton, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(binding.disconnectButton, com.google.android.material.R.attr.colorOnPrimary)
        binding.connectButton.isEnabled = !connected && !busy && canConnect
        binding.disconnectButton.isEnabled = connected && !busy
        if (connected) {
            // Same enabled/disabled behaviour as a normal filled button (e.g. while an operation is busy).
            val onSurface = MaterialColors.getColor(binding.disconnectButton, com.google.android.material.R.attr.colorOnSurface)
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            binding.disconnectButton.backgroundTintList = ColorStateList(
                states, intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(onSurface, 31), primary)
            )
            binding.disconnectButton.setTextColor(
                ColorStateList(states, intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(onSurface, 97), onPrimary))
            )
            binding.disconnectButton.strokeColor = ColorStateList.valueOf(primary)
            binding.disconnectButton.rippleColor = ColorStateList.valueOf(onPrimary).withAlpha(0x33)
        } else {
            binding.disconnectButton.backgroundTintList = originalBackground
            binding.disconnectButton.setTextColor(originalTextColors)
            binding.disconnectButton.strokeColor = originalStroke
            binding.disconnectButton.rippleColor = originalRipple
        }
    }

    /** Whether the Connect/Flash/Read/Verify buttons should target [hexUploadFragment] instead of [viewModel]. */
    private fun isArduinoHexMode(): Boolean = viewModel.uiState.value.deviceMode == DeviceMode.ARDUINO_HEX_UPLOAD

    private fun setupFileSlots() {
        for (slot in slotOrder) {
            val rowBinding = ItemFileSlotBinding.inflate(LayoutInflater.from(this), binding.fileSlotsContainer, false)
            rowBinding.slotLabel.text = slot.label
            rowBinding.slotEnabledCheck.setOnCheckedChangeListener { _, checked ->
                viewModel.setSlotEnabled(slot, checked)
            }
            rowBinding.slotBrowseButton.setOnClickListener {
                pendingSlot = slot
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
            rowBinding.slotDeleteButton.setOnClickListener { viewModel.clearFile(slot) }
            rowBinding.slotOffsetInput.afterTextChangedListener({ text ->
                if (!suppressOffsetWatchers) viewModel.offsetTextChanged(slot, text?.toString().orEmpty())
            })
            if (slot == ImageSlot.SPIFFS) {
                rowBinding.slotDetectButton.setOnClickListener { viewModel.detectSpiffs() }
                rowBinding.slotGetButton.setOnClickListener { extractSpiffsFolderLauncher.launch(null) }
                rowBinding.slotGenerateButton.setOnClickListener { openSpiffsFolderLauncher.launch(null) }
                rowBinding.slotEraseButton.setOnClickListener { confirmThenEraseSpiffs() }
            }
            if (slot == ImageSlot.SIM800_ROM) {
                rowBinding.slotSim800FormatCheck.setOnCheckedChangeListener { _, checked -> viewModel.setSim800FormatFat(checked) }
                rowBinding.slotSim800RebrandButton.setOnClickListener {
                    createRebrandFileLauncher.launch("ROM_VIVA_rebranded.bin")
                }
                rowBinding.slotSim800ImeiButton.setOnClickListener { promptSim800Imei() }
            }
            binding.fileSlotsContainer.addView(rowBinding.root)
            slotViews[slot] = rowBinding
        }
        binding.addCustomEntryButton.setOnClickListener {
            pendingCustomAdd = true
            openDocumentLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun setupDropdowns() {
        setupExposedDropdown(binding.baudRateDropdown, SerialConfig.COMMON_BAUD_RATES.map { it.toString() })
        setupExposedDropdown(binding.dataBitsDropdown, DataBits.entries.map { it.value.toString() })
        setupExposedDropdown(binding.stopBitsDropdown, StopBits.entries.map { it.label })
        setupExposedDropdown(binding.parityDropdown, Parity.entries.map { it.label })
        setupExposedDropdown(binding.flowControlDropdown, FlowControl.entries.map { it.label })
        setupExposedDropdown(binding.uploadBaudRateDropdown, SerialConfig.COMMON_BAUD_RATES.map { it.toString() })
        setupExposedDropdown(binding.deviceModeDropdown, DeviceMode.entries.map { it.label })
        setupExposedDropdown(binding.readSizePresetDropdown, FlashLayout.READ_SIZE_PRESETS.map { it.label })

        binding.baudRateDropdown.setText(SerialConfig().baudRate.toString(), false)
        binding.dataBitsDropdown.setText(SerialConfig().dataBits.value.toString(), false)
        binding.stopBitsDropdown.setText(SerialConfig().stopBits.label, false)
        binding.parityDropdown.setText(SerialConfig().parity.label, false)
        binding.flowControlDropdown.setText(SerialConfig().flowControl.label, false)
        binding.uploadBaudRateDropdown.setText(viewModel.uiState.value.uploadBaudRate.toString(), false)
        binding.deviceModeDropdown.setText(viewModel.uiState.value.deviceMode.label, false)

        val onConfigChanged = { _: Any? ->
            val config = SerialConfig(
                baudRate = binding.baudRateDropdown.text.toString().toIntOrNull() ?: 115200,
                dataBits = DataBits.entries.firstOrNull { it.value.toString() == binding.dataBitsDropdown.text.toString() } ?: DataBits.EIGHT,
                stopBits = StopBits.entries.firstOrNull { it.label == binding.stopBitsDropdown.text.toString() } ?: StopBits.ONE,
                parity = Parity.entries.firstOrNull { it.label == binding.parityDropdown.text.toString() } ?: Parity.NONE,
                flowControl = FlowControl.entries.firstOrNull { it.label == binding.flowControlDropdown.text.toString() } ?: FlowControl.NONE
            )
            viewModel.updateSerialConfig(config)
        }
        binding.baudRateDropdown.afterTextChangedListener({ onConfigChanged(null) })
        binding.dataBitsDropdown.setOnItemClickListener { _, _, _, _ -> onConfigChanged(null) }
        binding.stopBitsDropdown.setOnItemClickListener { _, _, _, _ -> onConfigChanged(null) }
        binding.parityDropdown.setOnItemClickListener { _, _, _, _ -> onConfigChanged(null) }
        binding.flowControlDropdown.setOnItemClickListener { _, _, _, _ -> onConfigChanged(null) }

        binding.uploadBaudRateDropdown.afterTextChangedListener({ text ->
            text?.toString()?.toIntOrNull()?.let { viewModel.updateUploadBaudRate(it) }
        })
        binding.uploadBaudRateDropdown.setOnItemClickListener { _, _, _, _ ->
            binding.uploadBaudRateDropdown.text.toString().toIntOrNull()?.let { viewModel.updateUploadBaudRate(it) }
        }
        binding.deviceModeDropdown.setOnItemClickListener { _, _, position, _ ->
            viewModel.setDeviceMode(DeviceMode.entries[position])
        }

        binding.deviceDropdown.setOnItemClickListener { _, _, position, _ ->
            val state = viewModel.uiState.value
            state.availableDevices.getOrNull(position)?.let { viewModel.selectDevice(it) }
        }
    }

    private fun setupExposedDropdown(view: AutoCompleteTextView, items: List<String>) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
    }

    /** Runs [action] on the Hex Uploader after the serial monitor (if running) has released the USB device. */
    private fun hexAction(action: ArduinoHexUploadFragment.() -> Unit) {
        lifecycleScope.launch {
            viewModel.stopMonitor().join()
            hexUploadFragment()?.action()
        }
    }

    private fun setupMonitor() {
        binding.monitorRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.monitorRecyclerView.adapter = monitorAdapter
        binding.monitorRecyclerView.itemAnimator = null

        binding.monitorStartStopButton.setOnClickListener {
            val st = viewModel.monitorState.value
            if (st.running || st.starting) viewModel.stopMonitor() else viewModel.startMonitor()
        }
        binding.monitorResetButton.setOnClickListener {
            if (!viewModel.monitorResetBoard()) Toast.makeText(this, R.string.monitor_not_running, Toast.LENGTH_SHORT).show()
        }
        binding.monitorSendButton.setOnClickListener { sendMonitorInput() }
        binding.monitorInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendMonitorInput()
                true
            } else {
                false
            }
        }
        binding.monitorClearButton.setOnClickListener { viewModel.monitorClear() }
        binding.monitorSettingsButton.setOnClickListener { showMonitorSettings() }
        binding.monitorCopyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Serial monitor", viewModel.monitorText()))
        }
    }

    /** Line ending, baud rate, autoscroll and timestamp - applied immediately, like the Arduino IDE's monitor options. */
    private fun showMonitorSettings() {
        val dialogBinding = DialogMonitorSettingsBinding.inflate(layoutInflater)
        val current = viewModel.monitorState.value

        val lineEndings = listOf(
            LineEnding.NONE to getString(R.string.monitor_le_none),
            LineEnding.NEWLINE to getString(R.string.monitor_le_nl),
            LineEnding.CARRIAGE_RETURN to getString(R.string.monitor_le_cr),
            LineEnding.BOTH to getString(R.string.monitor_le_both),
        )
        setupExposedDropdown(dialogBinding.monitorLineEndingDropdown, lineEndings.map { it.second })
        dialogBinding.monitorLineEndingDropdown.setText(lineEndings.first { it.first == current.lineEnding }.second, false)
        dialogBinding.monitorLineEndingDropdown.setOnItemClickListener { _, _, position, _ ->
            lineEndings.getOrNull(position)?.let { viewModel.monitorSetLineEnding(it.first) }
        }

        setupExposedDropdown(dialogBinding.monitorBaudDropdown, SerialMonitor.BAUD_RATES.map { it.toString() })
        dialogBinding.monitorBaudDropdown.setText(current.baud.toString(), false)
        dialogBinding.monitorBaudDropdown.setOnItemClickListener { _, _, position, _ ->
            SerialMonitor.BAUD_RATES.getOrNull(position)?.let { viewModel.monitorSetBaud(it) }
        }

        dialogBinding.monitorAutoscrollCheck.isChecked = current.autoscroll
        dialogBinding.monitorAutoscrollCheck.setOnCheckedChangeListener { _, checked -> viewModel.monitorSetAutoscroll(checked) }
        dialogBinding.monitorTimestampCheck.isChecked = current.showTimestamp
        dialogBinding.monitorTimestampCheck.setOnCheckedChangeListener { _, checked -> viewModel.monitorSetShowTimestamp(checked) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.monitor_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.monitor_done, null)
            .show()
    }

    private fun sendMonitorInput() {
        val text = binding.monitorInput.text?.toString().orEmpty()
        if (viewModel.monitorSend(text)) {
            binding.monitorInput.text?.clear()
        } else {
            Toast.makeText(this, R.string.monitor_not_running, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderMonitor(state: MonitorState) {
        val active = state.running || state.starting
        binding.monitorStartStopButton.setText(if (active) R.string.monitor_stop else R.string.monitor_start)
        binding.monitorStartStopButton.isEnabled = !state.starting
        binding.monitorSendButton.isEnabled = state.running
        binding.monitorResetButton.isEnabled = state.running
        // No text at all while stopped; just the live status while opening/running.
        binding.monitorStatusText.visibility = if (active) View.VISIBLE else View.GONE
        binding.monitorStatusText.text = when {
            state.starting -> getString(R.string.monitor_status_opening)
            state.running -> getString(R.string.monitor_status_running, state.deviceLabel, state.baud)
            else -> ""
        }

        monitorAdapter.setShowTimestamp(state.showTimestamp)
        monitorAdapter.setFontSize(viewModel.uiState.value.consoleFontSizeSp.toFloat())
        monitorAdapter.submitList(state.lines) {
            if (state.autoscroll && state.lines.isNotEmpty()) {
                binding.monitorRecyclerView.scrollToPosition(state.lines.size - 1)
            }
        }
    }

    private fun setupConsole() {
        binding.consoleRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.consoleRecyclerView.adapter = logAdapter
    }

    private fun setupListeners() {
        binding.serialSettingsToggleRow.setOnClickListener {
            serialSettingsExpanded = !serialSettingsExpanded
            binding.serialSettingsGroup.visibility = if (serialSettingsExpanded) View.VISIBLE else View.GONE
            binding.serialSettingsChevron.rotation = if (serialSettingsExpanded) 180f else 0f
        }
        binding.credentialsToggleRow.setOnClickListener {
            credentialsExpanded = !credentialsExpanded
            binding.credentialsGroup.visibility = if (credentialsExpanded) View.VISIBLE else View.GONE
            binding.credentialsChevron.rotation = if (credentialsExpanded) 180f else 0f
        }
        binding.uploadDeviceToggleRow.setOnClickListener {
            uploadDeviceExpanded = !uploadDeviceExpanded
            binding.uploadDeviceGroup.visibility = if (uploadDeviceExpanded) View.VISIBLE else View.GONE
            binding.uploadDeviceChevron.rotation = if (uploadDeviceExpanded) 180f else 0f
        }
        binding.flashOptionsToggleRow.setOnClickListener {
            flashOptionsExpanded = !flashOptionsExpanded
            binding.flashOptionsGroup.visibility = if (flashOptionsExpanded) View.VISIBLE else View.GONE
            binding.flashOptionsChevron.rotation = if (flashOptionsExpanded) 180f else 0f
        }
        binding.refreshDevicesButton.setOnClickListener { viewModel.refreshDevices() }
        // Device mode ARDUINO_HEX_UPLOAD means the HEX UPLOADER tab's own AVR
        // Programmer/USBasp/ArduinoISP managers (ArduinoHexUploadFragment's
        // usbSerialManager/usbaspManager/arduinoIspManager) should handle
        // Connect/Flash/Read/Verify instead of this screen's own ESP-style
        // viewModel equivalents - the Hex tab's own connect row, Upload FAB,
        // and USBasp/ArduinoISP Read/Verify buttons are all hidden now (see
        // activity_arduino_hex_upload.xml), so these four buttons are their
        // only remaining entry points. Every other Device mode is unaffected.
        binding.connectButton.setOnClickListener {
            if (isArduinoHexMode()) {
                hexSessionReleased = false
                hexUploadFragment()?.requestDeviceConnection()
                render(viewModel.uiState.value)
            } else {
                viewModel.connect()
            }
        }
        binding.disconnectButton.setOnClickListener {
            if (isArduinoHexMode()) {
                // The Hex Uploader opens its programmer per operation (there is no
                // persistent port to close), so Disconnect just releases the session shown
                // on these buttons until Connect is pressed again or the board is re-plugged.
                hexSessionReleased = true
                render(viewModel.uiState.value)
            } else {
                viewModel.disconnect()
            }
        }
        binding.resetButton.setOnClickListener { viewModel.resetDevice() }
        binding.bootloaderButton.setOnClickListener { viewModel.enterBootloader() }

        binding.detectButton.setOnClickListener { viewModel.detect() }
        binding.eraseButton.setOnClickListener { confirmThenErase() }
        binding.flashButton.setOnClickListener {
            if (isArduinoHexMode()) hexAction { requestUpload() } else viewModel.flash()
        }
        binding.verifyButton.setOnClickListener {
            if (isArduinoHexMode()) hexAction { requestVerify() } else viewModel.verify()
        }
        binding.readButton.setOnClickListener {
            if (isArduinoHexMode()) {
                hexAction { requestRead() }
            } else {
                createDumpFileLauncher.launch("flash_dump.bin")
            }
        }
        binding.stopButton.setOnClickListener { viewModel.stop() }

        val optionsChanged = View.OnClickListener { updateFlashOptionsFromUi() }
        binding.verifyAfterWriteCheck.setOnClickListener(optionsChanged)
        binding.eraseBeforeWriteCheck.setOnClickListener(optionsChanged)
        binding.compressDataCheck.setOnClickListener(optionsChanged)
        binding.resetAfterFlashingCheck.setOnClickListener(optionsChanged)
        binding.skipChecksumCheck.setOnClickListener(optionsChanged)
        binding.keepUsbConnectionCheck.setOnClickListener(optionsChanged)
        binding.useStubLoaderCheck.setOnClickListener(optionsChanged)

        binding.copyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ESP Flasher Log", viewModel.logAsText()))
        }
        binding.clearLogButton.setOnClickListener { viewModel.clearLog() }
        binding.saveLogButton.setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            createLogFileLauncher.launch("esp_flasher_log_$stamp.txt")
        }

        binding.readOffsetInput.afterTextChangedListener({ viewModel.advancedReadOffsetChanged(it?.toString().orEmpty()) })
        binding.readLengthInput.afterTextChangedListener({ viewModel.advancedReadLengthChanged(it?.toString().orEmpty()) })
        binding.readSizePresetDropdown.setOnItemClickListener { _, _, position, _ ->
            FlashLayout.READ_SIZE_PRESETS.getOrNull(position)?.let { viewModel.applyReadSizePreset(it.bytes) }
        }

        val credentialsChanged = { _: CharSequence? -> updateFirmwareCredentialsFromUi() }
        binding.credentialSsidInput.afterTextChangedListener(credentialsChanged)
        binding.credentialPasswordInput.afterTextChangedListener(credentialsChanged)
        binding.credentialTokenInput.afterTextChangedListener(credentialsChanged)
        binding.credentialOtaAuthInput.afterTextChangedListener(credentialsChanged)

        val sim800CredentialsChanged = { _: CharSequence? -> updateSim800CredentialsFromUi() }
        binding.credentialSim800FirmwareRevisionInput.afterTextChangedListener(sim800CredentialsChanged)
        binding.credentialSim800ProductNameInput.afterTextChangedListener(sim800CredentialsChanged)
        binding.credentialSim800FirmwareVersionInput.afterTextChangedListener(sim800CredentialsChanged)
        binding.credentialSim800ModuleNameInput.afterTextChangedListener(sim800CredentialsChanged)
        binding.credentialSim800VersionShortInput.afterTextChangedListener(sim800CredentialsChanged)
    }

    private fun confirmThenErase() {
        val chip = viewModel.uiState.value.deviceInfo?.chip
        if (chip?.isEsp32Series == true) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_erase)
                .setMessage(R.string.erase_confirm_message_esp32)
                .setPositiveButton(R.string.erase_option_all) { _, _ -> viewModel.erase() }
                .setNeutralButton(R.string.erase_option_app_partition) { _, _ -> viewModel.eraseAppAndPartitionTable() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_erase)
                .setMessage(R.string.erase_confirm_message)
                .setPositiveButton(R.string.action_erase) { _, _ -> viewModel.erase() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmThenEraseSpiffs() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_erase_spiffs)
            .setMessage("This erases just the SPIFFS partition (offset+size read from the last Partition Table file you picked), not the whole chip. Continue?")
            .setPositiveButton(R.string.action_erase_spiffs) { _, _ -> viewModel.eraseSpiffsOnly() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Lets the person confirm/edit the TAC (Type Allocation Code) the new
     * IMEI is generated from before running Change IMEI - pre-filled with
     * the same default ("35581309") the reference sim800_change_imei.py
     * hardcoded, but editable since a real TAC identifies a specific
     * device model and the person's module may not match that one.
     */
    private fun promptSim800Imei() {
        val input = android.widget.EditText(this).apply {
            setText(getString(R.string.sim800_default_tac))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSelection(text.length)
        }
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_change_imei)
            .setMessage(R.string.sim800_imei_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.action_change_imei) { _, _ ->
                val tac = input.text.toString().trim()
                if (tac.isNotEmpty()) viewModel.changeSim800Imei(tac)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateFlashOptionsFromUi() {
        viewModel.updateFlashOptions(
            FlashOptions(
                verifyAfterWrite = binding.verifyAfterWriteCheck.isChecked,
                eraseBeforeWrite = binding.eraseBeforeWriteCheck.isChecked,
                compressData = binding.compressDataCheck.isChecked,
                resetAfterFlashing = binding.resetAfterFlashingCheck.isChecked,
                skipChecksum = binding.skipChecksumCheck.isChecked,
                keepUsbConnection = binding.keepUsbConnectionCheck.isChecked,
                useStubLoader = binding.useStubLoaderCheck.isChecked
            )
        )
    }

    private fun updateFirmwareCredentialsFromUi() {
        viewModel.updateFirmwareCredentials(
            FirmwareCredentials(
                token = binding.credentialTokenInput.text?.toString().orEmpty(),
                ssid = binding.credentialSsidInput.text?.toString().orEmpty(),
                password = binding.credentialPasswordInput.text?.toString().orEmpty(),
                otaAuth = binding.credentialOtaAuthInput.text?.toString().orEmpty()
            )
        )
    }

    private fun updateSim800CredentialsFromUi() {
        viewModel.updateSim800Credentials(
            Sim800Credentials(
                firmwareRevision = binding.credentialSim800FirmwareRevisionInput.text?.toString().orEmpty(),
                productName = binding.credentialSim800ProductNameInput.text?.toString().orEmpty(),
                firmwareVersion = binding.credentialSim800FirmwareVersionInput.text?.toString().orEmpty(),
                moduleName = binding.credentialSim800ModuleNameInput.text?.toString().orEmpty(),
                versionShort = binding.credentialSim800VersionShortInput.text?.toString().orEmpty()
            )
        )
    }

    // -----------------------------------------------------------------
    // State rendering
    // -----------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.monitorState.collect { renderMonitor(it) } }
            }
        }
    }

    private fun render(state: MainUiState) {
        binding.usbHostWarningText.visibility = if (state.usbHostSupported) View.GONE else View.VISIBLE

        if (!binding.uploadBaudRateDropdown.hasFocus() &&
            binding.uploadBaudRateDropdown.text.toString() != state.uploadBaudRate.toString()
        ) {
            binding.uploadBaudRateDropdown.setText(state.uploadBaudRate.toString(), false)
        }

        if (!binding.deviceModeDropdown.hasFocus() && binding.deviceModeDropdown.text.toString() != state.deviceMode.label) {
            binding.deviceModeDropdown.setText(state.deviceMode.label, false)
        }

        renderDeviceDropdown(state)
        renderDeviceInfoText(state)

        val hexMode = state.deviceMode == DeviceMode.ARDUINO_HEX_UPLOAD
        // ESP32/ESP8266/BW16/SIM800: connection state comes from the ViewModel. Arduino Hex
        // Upload keeps its own connection inside ArduinoHexUploadFragment, so mirror that
        // (programmer attached + permission granted) instead.
        val connected = if (hexMode) hexDeviceReady && !hexSessionReleased else state.isConnected
        val busy = state.isBusy
        val isBw16 = state.deviceMode == DeviceMode.BW16_AMEBA
        val isSim800 = state.deviceMode == DeviceMode.SIM800
        // Arduino Hex Upload has its own connection, board/mode pickers, and
        // file browser (ArduinoHexUploadFragment, embedded in the HEX
        // UPLOADER tab) - none of this screen's connected/busy state applies
        // to it (that's tracked independently inside the fragment). Connect/
        // Flash/Read/Verify are routed to the fragment for this mode (see
        // setupListeners) and left enabled here; hexUploadFragment()'s own
        // startUpload()/startRead()/startVerify() already show their own
        // Snackbar if there's no hex file picked or no device connected yet,
        // the same guard they've always had. Detect/Erase/Reset/Bootloader
        // have no equivalent in this mode and stay disabled, same as before.
        val isArduinoHex = state.deviceMode == DeviceMode.ARDUINO_HEX_UPLOAD
        renderConnectionButtons(
            connected = connected,
            busy = busy,
            canConnect = isArduinoHex || state.availableDevices.isNotEmpty(),
        )
        // SIM800 has no software-driven reset-to-run or enter-download-mode
        // sequence at all (see MainViewModel#resetDevice/enterBootloader) -
        // both are either manual (power-cycle the module) or already baked
        // into Flash itself, so there's nothing for these buttons to do.
        binding.resetButton.isEnabled = connected && !busy && !isSim800 && !isArduinoHex
        binding.bootloaderButton.isEnabled = connected && !busy && !isSim800 && !isArduinoHex
        // Ameba has no equivalent of a non-destructive Detect step, and
        // Verify/Read for it aren't implemented separately - verify already
        // runs automatically as the last step of Flash, see AmebaOperationManager.
        // SIM800's protocol (see Sim800Protocol) has no Detect, Verify, or
        // Read/dump commands at all either.
        binding.detectButton.isEnabled = connected && !busy && !isBw16 && !isSim800 && !isArduinoHex
        // SIM800 also has no separate erase - "Format FAT before writing"
        // next to its ROM slot folds that into Flash instead.
        binding.eraseButton.isEnabled = connected && !busy && !isSim800 && !isArduinoHex
        binding.flashButton.isEnabled = if (isArduinoHex) true else (connected && !busy)
        binding.verifyButton.isEnabled = if (isArduinoHex) true else (connected && !busy && !isBw16 && !isSim800)
        binding.readButton.isEnabled = if (isArduinoHex) true else (connected && !busy && !isBw16 && !isSim800)
        binding.stopButton.isEnabled = busy

        // The HEX UPLOADER tab now embeds ArduinoHexUploadFragment directly
        // (see activity_main.xml's hexTabContent + setupTabs/showTab below)
        // instead of a launch card, so it's always there regardless of this
        // dropdown - it isn't gated on isArduinoHex at all any more.
        // Nothing left to show there once visibleSlots is empty for this
        // mode (see renderFileSlots) - hide the whole card rather than
        // leave an empty "File Selection" shell.
        binding.filesCard.visibility = if (isArduinoHex) View.GONE else View.VISIBLE

        renderFileSlots(state)
        renderCustomEntries(state)
        renderFlashOptions(state.flashOptions)
        renderFirmwareCredentials(state)
        renderProgress(state)
        renderDeviceInfoPanel(state)
        renderLog(state)
        renderAdvanced(state)
    }

    private fun renderDeviceDropdown(state: MainUiState) {
        val labels = state.availableDevices.map { "${it.displayLabel} (${it.vidPidHex})" }
        val current = binding.deviceDropdown.tag as? List<*>
        if (current != labels) {
            binding.deviceDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
            binding.deviceDropdown.tag = labels
        }
        val selectedIndex = state.availableDevices.indexOfFirst { it.androidDeviceName == state.selectedDeviceName }
        if (selectedIndex >= 0 && binding.deviceDropdown.text.toString() != labels[selectedIndex]) {
            binding.deviceDropdown.setText(labels[selectedIndex], false)
        } else if (state.availableDevices.isEmpty()) {
            binding.deviceDropdown.setText("", false)
        }
    }

    private fun renderDeviceInfoText(state: MainUiState) {
        val info: UsbDeviceInfo? = state.availableDevices.firstOrNull { it.androidDeviceName == state.selectedDeviceName }
        binding.deviceInfoText.text = if (info == null) {
            "No device selected"
        } else {
            buildString {
                append("VID:PID ${info.vidPidHex}  [${info.chipFamily.label}]\n")
                append("Manufacturer: ${info.manufacturerName ?: "—"}\n")
                append("Product: ${info.productName ?: "—"}\n")
                append("Serial: ${info.serialNumber ?: "— (grant permission to view)"}")
            }
        }
    }

    private fun renderFileSlots(state: MainUiState) {
        suppressOffsetWatchers = true
        val hasSpiffsPartition = state.partitionTable?.findSpiffs() != null
        val visibleSlots = when (state.deviceMode) {
            DeviceMode.BW16_AMEBA -> bw16Slots
            DeviceMode.SIM800 -> sim800Slots
            DeviceMode.ARDUINO_HEX_UPLOAD -> emptyList()
            DeviceMode.AUTO_ESP -> espSlots
        }
        for (slot in slotOrder) {
            val row = slotViews[slot] ?: continue
            row.root.visibility = if (slot in visibleSlots) View.VISIBLE else View.GONE
            if (slot !in visibleSlots) continue

            val file = state.files[slot]
            row.slotFileName.text = file?.fileName ?: getString(R.string.no_file_selected)

            val enabled = slot in state.enabledSlots
            if (row.slotEnabledCheck.isChecked != enabled) {
                row.slotEnabledCheck.setOnCheckedChangeListener(null)
                row.slotEnabledCheck.isChecked = enabled
                row.slotEnabledCheck.setOnCheckedChangeListener { _, checked -> viewModel.setSlotEnabled(slot, checked) }
            }
            row.slotEnabledCheck.isEnabled = file != null

            val offsetText = state.offsetText[slot].orEmpty()
            if (row.slotOffsetInput.text?.toString() != offsetText && !row.slotOffsetInput.hasFocus()) {
                row.slotOffsetInput.setText(offsetText)
            }
            // SIM800's protocol has no addressable offset at all - the whole
            // ROM is one blob - so that field would just be confusing clutter.
            row.slotOffsetContainer.visibility = if (slot == ImageSlot.SIM800_ROM) View.GONE else View.VISIBLE

            if (slot == ImageSlot.SPIFFS) {
                row.slotSpiffsToolsRow.visibility = View.VISIBLE
                row.slotDetectButton.isEnabled = !state.isBusy && state.isConnected
                row.slotGetButton.isEnabled = !state.isBusy && state.isConnected && hasSpiffsPartition
                row.slotGenerateButton.isEnabled = !state.isBusy
                row.slotEraseButton.isEnabled = !state.isBusy && state.isConnected && hasSpiffsPartition
            }
            if (slot == ImageSlot.SIM800_ROM) {
                row.slotSim800ToolsRow.visibility = View.VISIBLE
                if (row.slotSim800FormatCheck.isChecked != state.sim800FormatFat) {
                    row.slotSim800FormatCheck.setOnCheckedChangeListener(null)
                    row.slotSim800FormatCheck.isChecked = state.sim800FormatFat
                    row.slotSim800FormatCheck.setOnCheckedChangeListener { _, checked -> viewModel.setSim800FormatFat(checked) }
                }
                row.slotSim800RebrandButton.isEnabled = !state.isBusy && file != null
                // Change IMEI talks AT commands over an already-open connection
                // (the module's normal firmware, not its boot ROM) - it just
                // needs Connect, not a ROM file picked.
                row.slotSim800ImeiButton.isEnabled = !state.isBusy && state.isConnected
            }
        }
        suppressOffsetWatchers = false
    }

    private fun renderCustomEntries(state: MainUiState) {
        val currentIds = state.customEntries.map { it.id }.toSet()

        // Remove views for entries that no longer exist.
        val toRemove = customEntryViews.keys - currentIds
        for (id in toRemove) {
            customEntryViews.remove(id)?.let { binding.customEntriesContainer.removeView(it.root) }
        }

        // Add or update a view per entry.
        for (entry in state.customEntries) {
            var rowBinding = customEntryViews[entry.id]
            if (rowBinding == null) {
                rowBinding = io.espflasher.app.databinding.ItemCustomEntryBinding.inflate(
                    LayoutInflater.from(this), binding.customEntriesContainer, false
                )
                rowBinding.entryDeleteButton.setOnClickListener { viewModel.removeCustomEntry(entry.id) }
                rowBinding.entryOffsetInput.afterTextChangedListener { text ->
                    if (!suppressOffsetWatchers) viewModel.customEntryOffsetChanged(entry.id, text?.toString().orEmpty())
                }
                binding.customEntriesContainer.addView(rowBinding.root)
                customEntryViews[entry.id] = rowBinding
            }

            rowBinding.entryFileName.text = entry.fileName

            if (rowBinding.entryEnabledCheck.isChecked != entry.enabled) {
                rowBinding.entryEnabledCheck.setOnCheckedChangeListener(null)
                rowBinding.entryEnabledCheck.isChecked = entry.enabled
            }
            rowBinding.entryEnabledCheck.setOnCheckedChangeListener { _, checked ->
                viewModel.setCustomEntryEnabled(entry.id, checked)
            }

            if (rowBinding.entryOffsetInput.text?.toString() != entry.offsetText && !rowBinding.entryOffsetInput.hasFocus()) {
                suppressOffsetWatchers = true
                rowBinding.entryOffsetInput.setText(entry.offsetText)
                suppressOffsetWatchers = false
            }
        }
    }

    private fun renderFlashOptions(options: FlashOptions) {
        binding.verifyAfterWriteCheck.isChecked = options.verifyAfterWrite
        binding.eraseBeforeWriteCheck.isChecked = options.eraseBeforeWrite
        binding.compressDataCheck.isChecked = options.compressData
        binding.resetAfterFlashingCheck.isChecked = options.resetAfterFlashing
        binding.skipChecksumCheck.isChecked = options.skipChecksum
        binding.keepUsbConnectionCheck.isChecked = options.keepUsbConnection
        binding.useStubLoaderCheck.isChecked = options.useStubLoader
    }

    /**
     * Same "only overwrite if it actually differs and isn't focused" guard
     * as [renderCustomEntries]'s offset fields - without it, every render
     * pass triggered by the field's own text watcher would call setText()
     * again and yank the cursor to the end mid-keystroke.
     */
    private fun renderFirmwareCredentials(state: MainUiState) {
        fun sync(field: com.google.android.material.textfield.TextInputEditText, value: String) {
            if (field.text?.toString() != value && !field.hasFocus()) {
                field.setText(value)
            }
        }

        // SIM800 has its own completely separate set of replacement values
        // (see io.espflasher.app.sim800.Sim800Credentials) - never the
        // ESP32/ESP8266 ones, and never both groups shown at once.
        val isSim800 = state.deviceMode == DeviceMode.SIM800
        binding.espCredentialsGroup.visibility = if (isSim800) View.GONE else View.VISIBLE
        binding.sim800CredentialsGroup.visibility = if (isSim800) View.VISIBLE else View.GONE

        val credentials = state.firmwareCredentials
        sync(binding.credentialSsidInput, credentials.ssid)
        sync(binding.credentialPasswordInput, credentials.password)
        sync(binding.credentialTokenInput, credentials.token)
        sync(binding.credentialOtaAuthInput, credentials.otaAuth)

        val sim800Credentials = state.sim800Credentials
        sync(binding.credentialSim800FirmwareRevisionInput, sim800Credentials.firmwareRevision)
        sync(binding.credentialSim800ProductNameInput, sim800Credentials.productName)
        sync(binding.credentialSim800FirmwareVersionInput, sim800Credentials.firmwareVersion)
        sync(binding.credentialSim800ModuleNameInput, sim800Credentials.moduleName)
        sync(binding.credentialSim800VersionShortInput, sim800Credentials.versionShort)
    }

    private fun renderProgress(state: MainUiState) {
        val p = state.progress
        binding.operationLabelText.text = if (state.isBusy) p.operationLabel else "Idle"
        binding.progressBar.progress = p.percent
        binding.progressBar.isIndeterminate = state.isBusy && p.bytesTotal == 0L
        binding.progressDetailText.text = if (p.bytesTotal > 0) {
            "%d%%  addr=0x%08X  %d / %d B  %s/s  ETA %ds".format(
                p.percent, p.currentAddress, p.bytesDone, p.bytesTotal,
                humanReadableBytes(p.speedBytesPerSec.toLong()), p.etaSeconds
            )
        } else ""
        binding.stubBadge.visibility = if (state.deviceInfo?.stubRunning == true) View.VISIBLE else View.GONE
    }

    private fun renderDeviceInfoPanel(state: MainUiState) {
        val info = state.deviceInfo
        binding.deviceInfoDetailText.text = if (info == null) {
            getString(R.string.no_device_detected)
        } else {
            buildString {
                append("Chip: ${info.chip.displayName}\n")
                append("Revision: ${info.revision}\n")
                append("MAC: ${info.macAddress}\n")
                append("Crystal: ${info.crystalFreqMHz} MHz (nominal)\n")
                append("Flash: ${info.flashSizeBytes?.let { io.espflasher.app.protocol.FlashLayout.humanReadableSize(it) } ?: "Unknown"}\n")
                append("Flash mode: ${info.flashMode ?: "N/A (select a firmware file to see its declared mode)"}\n")
                append("Features: ${info.features.joinToString(", ")}\n")
                append("Secure Boot: ${info.secureBootStatus}\n")
                append("Flash Encryption: ${info.flashEncryptionStatus}")
            }
        }
    }

    private fun renderLog(state: MainUiState) {
        logAdapter.setFontSize(state.consoleFontSizeSp.toFloat())
        logAdapter.submitList(state.logs) {
            if (state.logs.isNotEmpty()) binding.consoleRecyclerView.scrollToPosition(state.logs.size - 1)
        }
    }

    private fun renderAdvanced(state: MainUiState) {
        if (binding.readOffsetInput.text?.toString() != state.advancedReadOffsetText && !binding.readOffsetInput.hasFocus()) {
            binding.readOffsetInput.setText(state.advancedReadOffsetText)
        }
        if (binding.readLengthInput.text?.toString() != state.advancedReadLengthText && !binding.readLengthInput.hasFocus()) {
            binding.readLengthInput.setText(state.advancedReadLengthText)
        }
        // Reflect whichever preset (if any) matches the current Length back into
        // the picker - e.g. after Detect auto-fills a 4 MB chip's capacity, the
        // dropdown shows "4 MB" too instead of sitting blank next to a match.
        val matchingLabel = FlashLayout.parseAddress(state.advancedReadLengthText)
            ?.let { length -> FlashLayout.READ_SIZE_PRESETS.firstOrNull { it.bytes == length }?.label }
            .orEmpty()
        if (!binding.readSizePresetDropdown.hasFocus() && binding.readSizePresetDropdown.text.toString() != matchingLabel) {
            binding.readSizePresetDropdown.setText(matchingLabel, false)
        }
    }

    private fun humanReadableBytes(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> "%.1f MB".format(bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> "%.1f KB".format(bytesPerSec / 1024.0)
        else -> "$bytesPerSec B"
    }

    // -----------------------------------------------------------------
    // Content resolver helpers
    // -----------------------------------------------------------------

    private fun queryFileName(uri: android.net.Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun queryFileSize(uri: android.net.Uri): Long? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
        return null
    }
}

/** Small helper so plain EditText/AutoCompleteTextView views can use a lambda-based watcher. */
private fun android.widget.EditText.afterTextChangedListener(afterTextChanged: (CharSequence?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            afterTextChanged(s?.toString())
        }
    })
}
