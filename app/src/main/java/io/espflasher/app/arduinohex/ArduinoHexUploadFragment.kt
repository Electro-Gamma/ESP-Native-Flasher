package io.espflasher.app.arduinohex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.espflasher.app.R
import io.espflasher.app.databinding.ActivityArduinoHexUploadBinding
import io.espflasher.app.service.FlashForegroundService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.makeitall.arduino.ArduinoUploader.Config.Arduino
import kr.co.makeitall.arduino.ArduinoUploader.Config.McuIdentifier
import kr.co.makeitall.arduino.ArduinoUploader.Config.Protocol
import kr.co.makeitall.arduino.Boards
import kr.co.makeitall.arduino.LineReader
import kr.co.makeitall.arduino.UsbSerialManager
import kr.co.makeitall.arduino.arduinoisp.ArduinoIspManager
import kr.co.makeitall.arduino.usbasp.IntelHexWriter
import kr.co.makeitall.arduino.usbasp.UsbaspManager
import kr.co.makeitall.arduino.usbasp.UsbaspMcuInfo
import kr.co.makeitall.arduino.usbasp.UsbaspMcuTable
import kr.co.makeitall.arduino.usbasp.UsbaspMemoryType
import kr.co.makeitall.arduino.usbasp.UsbaspReadResult
import kr.co.makeitall.arduino.usbasp.UsbaspSckOption
import IntelHexFormatReader.HexFileReader
import IntelHexFormatReader.Model.MemoryBlock
import java.io.InputStreamReader

/**
 * A near-verbatim port of the reference `arduino-hex-upload` app's own
 * `MainActivity` - AVR programming (STK500v1/v2, AVR109 bootloaders,
 * USBasp, and ArduinoISP) is different enough from every other device mode
 * in this app (different protocols, different UI shape - a 2x2 mode toggle
 * rather than named file slots, and USBasp needs an entirely separate
 * raw-USB connection with no serial port at all - see [UsbaspManager]) that
 * reusing the reference app's own tested screen wholesale, rather than
 * re-deriving its behavior against this app's file-slot/[io.espflasher.app.flashing.FlashOperationManager]
 * architecture, is both safer (no risk of mistranscribing a working
 * protocol implementation) and more honest about what's actually shared
 * between the two.
 *
 * Originally this was `ArduinoHexUploadActivity`, reached via an "Open
 * Arduino Hex Upload" button that launched it as its own screen. It's now
 * `ArduinoHexUploadFragment` instead, embedded directly and permanently in
 * MainActivity's HEX UPLOADER tab (via the FragmentContainerView in
 * activity_main.xml) so it's always there with no button to tap first.
 * Nothing about *how* it uploads changed in that move - every field, every
 * method, the whole Upload/Verify/Read flow below is the same code that
 * used to live in the Activity, just re-housed. What did change is how it
 * talks to its surroundings, since a Fragment isn't a Window owner: no more
 * `setSupportActionBar`/edge-to-edge inset handling (MainActivity already
 * owns the one real window and toolbar; this content just lays out inside
 * whatever space its tab gets), `this`-as-Context became `requireContext()`,
 * `contentResolver`/`applicationContext`/`runOnUiThread`/`getSystemService`
 * became their `requireContext()`/`requireActivity()` equivalents, and
 * `onDestroy` became `onDestroyView`. This Fragment still owns its own USB
 * connection/upload state entirely independently of
 * [io.espflasher.app.ui.MainViewModel] - its own [usbSerialManager]/
 * [usbaspManager]/[arduinoIspManager] instances, exactly like the reference
 * app had as a standalone app. The difference from that reference app: this
 * screen's own toolbar, Upload FAB, and USBasp/ArduinoISP Read/Verify
 * buttons are all now hidden (see activity_arduino_hex_upload.xml), and
 * [io.espflasher.app.ui.MainActivity]'s own FLASH-tab Connect/Flash/Read/
 * Verify buttons drive the same click handlers from outside instead, via
 * [requestDeviceConnection]/[requestUpload]/[requestRead]/[requestVerify]
 * when Device mode is ARDUINO_HEX_UPLOAD - see those functions' own doc
 * comments. Every one of them just calls the exact same private function
 * its own (now-hidden) button always called; nothing about *how* any of
 * this works changed, only what's clickable on screen.
 *
 * Visual style: this tab is inflated with the Activity's own theme (Theme.EspNativeFlasher) and
 * uses the same card/section-title/button styles as the Flash and Files tabs, so all four tabs
 * look identical. (It used to wrap the inflater in the separate Theme.ArduinoHexUploader.) Every id referenced here via [binding]
 * still comes from the same `activity_arduino_hex_upload.xml` this Activity
 * used (kept under its old filename - see that file's own comment for why).
 * The layout/component styles are still the reference app's own (card
 * shape, button corner radius, toolbar layout), but the color palette
 * matches this app's own Theme.EspNativeFlasher (teal/cyan brand colors)
 * instead of the reference app's original teal+orange scheme - see
 * activity_arduino_hex_upload.xml, arduino_hex_upload_theme.xml,
 * arduino_hex_upload_colors.xml, and arduino_hex_upload_strings.xml's own
 * comments for exactly what changed (colors + name collisions only; no
 * behavior).
 */
class ArduinoHexUploadFragment : Fragment() {

    private enum class UploadMode { STANDARD, CUSTOM, USBASP, ARDUINO_ISP }

    private var _binding: ActivityArduinoHexUploadBinding? = null
    private val binding get() = _binding!!

    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var usbaspManager: UsbaspManager
    private lateinit var arduinoIspManager: ArduinoIspManager

    private val boards = Boards.values()
    private val mcus = McuIdentifier.values()
    private val protocols = Protocol.values()
    private val sckOptions = UsbaspSckOption.values()
    private val usbaspMcus = UsbaspMcuTable.all
    private val memoryTypes = UsbaspMemoryType.values()

    private var uploadMode = UploadMode.STANDARD
    private var selectedBoard: Boards = Boards.ARDUINO_UNO
    private var selectedMcu: McuIdentifier = McuIdentifier.AtMega328P
    private var selectedProtocol: Protocol = Protocol.Stk500v1
    private var selectedUsbaspMcu: UsbaspMcuInfo = UsbaspMcuTable.default
    private var selectedSckOption: UsbaspSckOption = UsbaspSckOption.AUTO
    private var selectedMemoryType: UsbaspMemoryType = UsbaspMemoryType.FLASH
    private var selectedArduinoIspMcu: UsbaspMcuInfo = UsbaspMcuTable.default
    private var selectedArduinoIspMemoryType: UsbaspMemoryType = UsbaspMemoryType.FLASH

    private var selectedHexUri: Uri? = null
    private var hexValidated = false
    private var isUploading = false
    private var pendingReadBytes: ByteArray? = null

    // Backs the shared FlashForegroundService notification while an
    // Upload/Read/Verify is running - see setUploading() and
    // mainThreadPercentCallback() below, and FlashForegroundService's own
    // class doc comment for how the two screens share it safely.
    private var currentProgressLabel: String = ""
    private var lastNotifiedPercent = -1

    private val openHexFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onHexFilePicked(it) }
        }

    private val saveReadResult =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            onSaveLocationPicked(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflated with the Activity's own theme (Theme.EspNativeFlasher) so this tab uses the
        // exact same colours, cards, buttons and typography as the Flash and Files tabs. It used
        // to be wrapped in the separate Theme.ArduinoHexUploader, which is what made it look different.
        _binding = ActivityArduinoHexUploadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Belt-and-suspenders alongside the XML attribute: makes sure this small log box
        // properly claims scroll gestures instead of losing them to the outer page ScrollView.
        binding.logScrollView.isNestedScrollingEnabled = true

        usbSerialManager = UsbSerialManager(requireContext(), viewLifecycleOwner.lifecycle)
        usbSerialManager
            .addOnStateListener { refreshDeviceStatus(); updateUploadEnabled() }
            .addOnPermissionListener { refreshDeviceStatus(); updateUploadEnabled() }
            .addOnErrorListener { refreshDeviceStatus(); updateUploadEnabled() }

        usbaspManager = UsbaspManager(requireContext(), viewLifecycleOwner.lifecycle)
        usbaspManager.addOnChangeListener { refreshDeviceStatus(); updateUploadEnabled() }

        // ArduinoISP uses a normal Arduino board — the same CH340/FTDI/CDC device already
        // managed by usbSerialManager — just speaking a different protocol over it, so it
        // doesn't need its own device-detection manager the way USBasp does.
        arduinoIspManager = ArduinoIspManager(requireContext(), usbSerialManager)

        setupUploadTypeToggle()
        setupBoardDropdown()
        setupCustomFields()
        setupUsbaspFields()
        setupArduinoIspFields()

        binding.browseButton.setOnClickListener { openHexFile.launch(arrayOf("*/*")) }
        binding.deviceStatusRow.setOnClickListener { onDeviceStatusRowClicked() }
        binding.uploadFab.setOnClickListener { startUpload() }
        binding.copyLogButton.setOnClickListener { copyLogToClipboard() }
        binding.usbaspReadButton.setOnClickListener { startRead() }
        binding.usbaspVerifyButton.setOnClickListener { startVerify() }
        binding.arduinoIspReadButton.setOnClickListener { startRead() }
        binding.arduinoIspVerifyButton.setOnClickListener { startVerify() }

        refreshDeviceStatus()
        updateUploadEnabled()
    }

    // region Setup

    /**
     * Four modes no longer fit a single-row RadioGroup, and RadioGroup only manages direct
     * children for mutual exclusion anyway (these are laid out as a 2x2 grid of plain
     * RadioButtons), so exclusivity is handled here instead.
     */
    private fun setupUploadTypeToggle() {
        val radios = listOf(binding.radioStandard, binding.radioCustom, binding.radioUsbasp, binding.radioArduinoIsp)
        val modes = listOf(UploadMode.STANDARD, UploadMode.CUSTOM, UploadMode.USBASP, UploadMode.ARDUINO_ISP)

        val onSelected: (RadioButton, UploadMode) -> Unit = { selected, mode ->
            radios.forEach { it.isChecked = (it === selected) }
            uploadMode = mode
            binding.boardInputLayout.visibility = if (mode == UploadMode.STANDARD) View.VISIBLE else View.GONE
            binding.customFieldsContainer.visibility = if (mode == UploadMode.CUSTOM) View.VISIBLE else View.GONE
            binding.usbaspFieldsContainer.visibility = if (mode == UploadMode.USBASP) View.VISIBLE else View.GONE
            binding.arduinoIspFieldsContainer.visibility = if (mode == UploadMode.ARDUINO_ISP) View.VISIBLE else View.GONE
            if (selectedHexUri != null) validateSelectedHex()
            refreshDeviceStatus()
            updateUploadEnabled()
        }

        radios.forEachIndexed { index, radio ->
            radio.setOnClickListener { onSelected(radio, modes[index]) }
        }
    }

    private fun setupBoardDropdown() {
        val names = boards.map { it.boardName }
        binding.boardDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        binding.boardDropdown.setText(selectedBoard.boardName, false)
        binding.boardDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedBoard = boards[position]
            if (selectedHexUri != null) validateSelectedHex()
        }
    }

    private fun setupCustomFields() {
        binding.mcuDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mcus.map { it.name })
        )
        binding.mcuDropdown.setText(selectedMcu.name, false)
        binding.mcuDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedMcu = mcus[position]
            if (selectedHexUri != null) validateSelectedHex()
        }

        binding.protocolDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, protocols.map { it.name })
        )
        binding.protocolDropdown.setText(selectedProtocol.name, false)
        binding.protocolDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedProtocol = protocols[position]
            binding.customBaudRate.setText(defaultBaudRateFor(selectedProtocol).toString())
        }
    }

    private fun setupUsbaspFields() {
        // 169 parts — AutoCompleteTextView supports type-to-filter out of the box, which is
        // how a list this size stays usable (matches ZFlasher-style search-as-you-type UX).
        binding.usbaspMcuDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, usbaspMcus.map { it.displayName })
        )
        binding.usbaspMcuDropdown.setText(selectedUsbaspMcu.displayName, false)
        binding.usbaspMcuDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedUsbaspMcu = usbaspMcus[position]
            if (selectedHexUri != null) validateSelectedHex()
        }

        binding.usbaspMemoryDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, memoryTypes.map { it.label })
        )
        binding.usbaspMemoryDropdown.setText(selectedMemoryType.label, false)
        binding.usbaspMemoryDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedMemoryType = memoryTypes[position]
            if (selectedHexUri != null) validateSelectedHex()
            updateUploadEnabled()
        }

        binding.usbaspSckDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, sckOptions.map { it.label })
        )
        binding.usbaspSckDropdown.setText(selectedSckOption.label, false)
        binding.usbaspSckDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedSckOption = sckOptions[position]
        }
    }

    private fun setupArduinoIspFields() {
        binding.arduinoIspMcuDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, usbaspMcus.map { it.displayName })
        )
        binding.arduinoIspMcuDropdown.setText(selectedArduinoIspMcu.displayName, false)
        binding.arduinoIspMcuDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedArduinoIspMcu = usbaspMcus[position]
            if (selectedHexUri != null) validateSelectedHex()
        }

        binding.arduinoIspMemoryDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, memoryTypes.map { it.label })
        )
        binding.arduinoIspMemoryDropdown.setText(selectedArduinoIspMemoryType.label, false)
        binding.arduinoIspMemoryDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedArduinoIspMemoryType = memoryTypes[position]
            if (selectedHexUri != null) validateSelectedHex()
            updateUploadEnabled()
        }
    }

    // endregion

    // region Hex file picking + validation

    private fun onHexFilePicked(uri: Uri) {
        selectedHexUri = uri
        binding.hexFileName.text = queryDisplayName(uri) ?: uri.lastPathSegment ?: uri.toString()
        validateSelectedHex()
    }

    private fun queryDisplayName(uri: Uri): String? =
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    /** The memory type relevant to whichever ISP-style mode (USBasp/ArduinoISP) is active. */
    private fun currentIspMemoryType(): UsbaspMemoryType =
        if (uploadMode == UploadMode.ARDUINO_ISP) selectedArduinoIspMemoryType else selectedMemoryType

    private fun currentIspMcu(): UsbaspMcuInfo =
        if (uploadMode == UploadMode.ARDUINO_ISP) selectedArduinoIspMcu else selectedUsbaspMcu

    /** Byte-size ceiling hex validation should check the selected file against. */
    private fun currentValidationSize(): Int = when (uploadMode) {
        UploadMode.STANDARD -> flashSizeFor(selectedBoard.chipType)
        UploadMode.CUSTOM -> flashSizeFor(selectedMcu)
        UploadMode.USBASP, UploadMode.ARDUINO_ISP -> {
            val mcu = currentIspMcu()
            if (currentIspMemoryType() == UsbaspMemoryType.EEPROM) mcu.eepromSize else mcu.flashSize
        }
    }

    private fun flashSizeFor(mcu: McuIdentifier): Int = when (mcu) {
        McuIdentifier.AtMega168 -> 16 * 1024
        McuIdentifier.AtMega328P -> 32 * 1024
        McuIdentifier.AtMega32U4 -> 32 * 1024
        McuIdentifier.AtMega1284, McuIdentifier.AtMega1284P -> 128 * 1024
        McuIdentifier.AtMega2560 -> 256 * 1024
    }

    private fun validateSelectedHex() {
        val uri = selectedHexUri ?: return
        val targetSize = currentValidationSize()

        binding.verifyRow.visibility = View.VISIBLE
        binding.verifyIcon.setImageResource(R.drawable.ic_check_circle_ahu)
        binding.verifyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ahu_neutral_500))
        binding.verifyText.text = getString(R.string.verify_checking)
        hexValidated = false
        updateUploadEnabled()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val lines = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        LineReader(InputStreamReader(input)).readLines()
                    } ?: throw IllegalStateException("Could not open the selected file")
                    HexFileReader(lines, targetSize).Parse() as MemoryBlock
                }
            }

            result.onSuccess { block ->
                val used = block.highestModifiedOffset + 1
                if (used > targetSize) {
                    showVerifyError(getString(R.string.verify_too_big_format, formatBytes(targetSize)))
                } else {
                    val pct = (used * 100 / targetSize).coerceIn(0, 100)
                    binding.verifyIcon.setImageResource(R.drawable.ic_check_circle_ahu)
                    binding.verifyText.setTextColor(ContextCompat.getColor(this@ArduinoHexUploadFragment.requireContext(), R.color.ahu_success_600))
                    binding.verifyText.text =
                        getString(R.string.verify_valid_format, formatBytes(used), formatBytes(targetSize), pct)
                    hexValidated = true
                }
                updateUploadEnabled()
            }.onFailure { e ->
                showVerifyError(e.message ?: e.toString())
            }
        }
    }

    private fun showVerifyError(message: String) {
        binding.verifyIcon.setImageResource(R.drawable.ic_error_circle_ahu)
        binding.verifyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ahu_error_600))
        binding.verifyText.text = getString(R.string.verify_invalid_format, message)
        hexValidated = false
        updateUploadEnabled()
    }

    private fun formatBytes(n: Int): String = "%,d".format(n)

    // endregion

    // region Device status

    private fun onDeviceStatusRowClicked() {
        if (uploadMode == UploadMode.USBASP) {
            if (usbaspManager.device != null && !usbaspManager.hasPermission()) {
                usbaspManager.requestPermission()
            }
        } else {
            if (usbSerialManager.usbDevice != null && !usbSerialManager.hasPermission()) {
                usbSerialManager.requestPermission()
            }
        }
    }

    /**
     * Public entry point for MainActivity's FLASH-tab "Connect" button (see
     * MainActivity#hexUploadFragment/setupListeners), used when Device mode
     * is DeviceMode.ARDUINO_HEX_UPLOAD. The Hex tab's own "Device status" row
     * that used to expose [onDeviceStatusRowClicked] directly is now hidden
     * (visibility="gone" in activity_arduino_hex_upload.xml) with the Connect
     * button standing in for it instead - this just calls the exact same,
     * unmodified click handler that row always used, so behavior (USBasp vs.
     * AVR-serial permission request, based on the currently selected upload
     * type) is identical to before.
     */
    fun requestDeviceConnection() {
        onDeviceStatusRowClicked()
    }

    /**
     * UI-only hook for MainActivity's FLASH-tab Connect/Disconnect buttons. Called with
     * `true` whenever the selected programmer (USBasp, or the AVR serial board used by
     * Standard/Custom/ArduinoISP) is attached AND has USB permission - i.e. exactly the
     * existing [isDeviceReady] condition the upload engine already relies on. It does not
     * touch any connection/upload code; it only lets the screen mirror that state.
     */
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    /** Same condition as [isDeviceReady], but safe to call before the view exists. */
    fun isProgrammerConnected(): Boolean = _binding != null && isDeviceReady()

    private fun refreshDeviceStatus() {
        renderDeviceStatus()
        if (_binding != null) onConnectionStateChanged?.invoke(isDeviceReady())
    }

    private fun renderDeviceStatus() {
        if (uploadMode == UploadMode.USBASP) {
            val device = usbaspManager.device
            when {
                device == null -> {
                    binding.deviceStatusText.text = getString(R.string.device_none_usbasp)
                    tintStatusIcon(R.color.ahu_neutral_500)
                }
                !usbaspManager.hasPermission() -> {
                    binding.deviceStatusText.text = getString(R.string.device_needs_permission)
                    tintStatusIcon(R.color.ahu_warning_700)
                }
                else -> {
                    binding.deviceStatusText.text = getString(R.string.device_ready_format, "USBasp programmer")
                    tintStatusIcon(R.color.ahu_success_600)
                }
            }
            return
        }

        // Standard/Custom/ArduinoISP all use the same connected serial board.
        val device = usbSerialManager.usbDevice
        when {
            device == null -> {
                binding.deviceStatusText.text = getString(R.string.device_none)
                tintStatusIcon(R.color.ahu_neutral_500)
            }
            !usbSerialManager.hasPermission() -> {
                binding.deviceStatusText.text = getString(R.string.device_needs_permission)
                tintStatusIcon(R.color.ahu_warning_700)
            }
            else -> {
                binding.deviceStatusText.text =
                    getString(R.string.device_ready_format, UsbChipIdentifier.friendlyName(device))
                tintStatusIcon(R.color.ahu_success_600)
            }
        }
    }

    private fun tintStatusIcon(colorRes: Int) {
        binding.deviceStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

    // endregion

    // region Upload / Verify / Read

    /**
     * Public entry points for MainActivity's FLASH-tab Flash/Read/Verify
     * buttons (see MainActivity#setupListeners), used when Device mode is
     * DeviceMode.ARDUINO_HEX_UPLOAD. The Hex tab's own Upload FAB and its
     * USBasp/ArduinoISP Read/Verify button rows are now hidden (see
     * activity_arduino_hex_upload.xml) with those three buttons standing in
     * for them instead - each just calls the exact same, unmodified
     * start*() function its own removed button always called below, so
     * behavior (including the "no hex file"/"no device" Snackbar guards
     * already inside each start*()) is identical to before. requestRead()
     * and requestVerify() already only make sense for USBasp/ArduinoISP -
     * startRead()/startVerify() branch on the currently selected upload
     * type as they always have, same as the row of buttons they replace.
     */
    fun requestUpload() {
        startUpload()
    }

    fun requestRead() {
        startRead()
    }

    fun requestVerify() {
        startVerify()
    }

    private fun defaultBaudRateFor(protocol: Protocol): Int = when (protocol) {
        Protocol.Stk500v1 -> 57600
        Protocol.Stk500v2 -> 115200
        Protocol.Avr109 -> 57600
    }

    private fun defaultResetBehaviorFor(protocol: Protocol): Pair<String?, String?> = when (protocol) {
        Protocol.Stk500v1 -> "DTR;true" to "DTR-RTS;250;50"
        Protocol.Stk500v2 -> "DTR-RTS;50;250;true" to "DTR-RTS;250;50;true"
        Protocol.Avr109 -> "1200bps" to null
    }

    private fun isDeviceReady(): Boolean = if (uploadMode == UploadMode.USBASP) {
        usbaspManager.device != null && usbaspManager.hasPermission()
    } else {
        usbSerialManager.usbDevice != null && usbSerialManager.hasPermission()
    }

    /** Whether the active mode's selected memory type can be written/verified (Flash/EEPROM only). */
    private fun isWritableMemoryType(): Boolean = when (uploadMode) {
        UploadMode.USBASP, UploadMode.ARDUINO_ISP ->
            currentIspMemoryType() == UsbaspMemoryType.FLASH || currentIspMemoryType() == UsbaspMemoryType.EEPROM
        else -> true
    }

    private fun updateUploadEnabled() {
        val deviceReady = isDeviceReady()
        val writable = isWritableMemoryType()

        val uploadReady = !isUploading && hexValidated && deviceReady && writable
        binding.uploadFab.isEnabled = uploadReady
        binding.uploadFab.alpha = if (uploadReady) 1f else 0.5f

        val ispBaseReady = !isUploading && deviceReady
        binding.usbaspReadButton.isEnabled = ispBaseReady
        binding.usbaspVerifyButton.isEnabled = ispBaseReady && hexValidated && writable
        binding.arduinoIspReadButton.isEnabled = ispBaseReady
        binding.arduinoIspVerifyButton.isEnabled = ispBaseReady && hexValidated && writable
    }

    /**
     * [label] is only applied while turning the progress row ON - it drove
     * the same fixed "Uploading…" text for Upload, Read, *and* Verify
     * before, which is exactly why a USBasp Read used to show "Uploading…"
     * the whole time instead of "Reading…": all three called this with no
     * way to say which operation was actually running.
     *
     * Also starts/stops the shared [FlashForegroundService] notification
     * (owner [FlashForegroundService.OWNER_AVR]) so a long AVR
     * upload/read/verify keeps the app process alive if it's backgrounded,
     * the same protection the ESP Flasher screen already has - see that
     * service's class doc comment for how the two screens share it safely.
     */
    private fun setUploading(active: Boolean, label: CharSequence = getString(R.string.uploading)) {
        isUploading = active
        binding.progressContainer.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            binding.progressLabelText.text = label
            currentProgressLabel = label.toString()
            lastNotifiedPercent = -1
            FlashForegroundService.start(requireContext().applicationContext, "$currentProgressLabel 0%", FlashForegroundService.OWNER_AVR)
        } else {
            FlashForegroundService.stop(requireContext().applicationContext, FlashForegroundService.OWNER_AVR)
        }
        binding.browseButton.isEnabled = !active
        binding.radioStandard.isEnabled = !active
        binding.radioCustom.isEnabled = !active
        binding.radioUsbasp.isEnabled = !active
        binding.radioArduinoIsp.isEnabled = !active
        binding.boardDropdown.isEnabled = !active
        binding.mcuDropdown.isEnabled = !active
        binding.protocolDropdown.isEnabled = !active
        binding.customBaudRate.isEnabled = !active
        binding.customBoardName.isEnabled = !active
        binding.usbaspMcuDropdown.isEnabled = !active
        binding.usbaspMemoryDropdown.isEnabled = !active
        binding.usbaspSckDropdown.isEnabled = !active
        binding.arduinoIspMcuDropdown.isEnabled = !active
        binding.arduinoIspMemoryDropdown.isEnabled = !active
        if (!active) binding.progressBar.setProgressCompat(0, false)
        updateUploadEnabled()
    }

    private fun appendLog(message: String) {
        if (binding.logText.text.isNullOrBlank()) {
            binding.logText.text = message
        } else {
            binding.logText.append("\n$message")
        }
        binding.logText.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val text = binding.logText.text?.toString().orEmpty()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Upload log", text))
        Snackbar.make(binding.root, R.string.log_copied, Snackbar.LENGTH_SHORT).show()
    }

    // UsbSerialManager/UsbaspManager/ArduinoIspManager all invoke these callbacks from a
    // background dispatcher while an operation is running, but the callback bodies touch Views
    // — so they must always hop back to the main thread. runOnUiThread executes immediately if
    // already on it, so this is free in the common case and just correct in the background case.
    private fun mainThreadPercentCallback(): (Int) -> Unit = { percent ->
        requireActivity().runOnUiThread {
            binding.progressBar.setProgressCompat(percent, true)
            binding.progressPercentText.text = "$percent%"
            // Throttled to every 5% - updating the system notification on
            // every single 1% tick would spam NotificationManager for no
            // visible benefit once the shade is already showing "running".
            if (percent - lastNotifiedPercent >= 5 || percent >= 100) {
                lastNotifiedPercent = percent
                FlashForegroundService.updateStatus(
                    requireContext().applicationContext, "$currentProgressLabel $percent%", FlashForegroundService.OWNER_AVR
                )
            }
        }
    }

    private fun mainThreadMessageCallback(): (String) -> Unit = { message ->
        requireActivity().runOnUiThread { appendLog(message) }
    }

    private fun startUpload() {
        val uri = selectedHexUri
        if (uri == null) {
            Snackbar.make(binding.root, R.string.error_pick_hex_first, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!isDeviceReady()) {
            Snackbar.make(binding.root, R.string.error_device_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        setUploading(true)
        binding.logText.text = ""

        lifecycleScope.launch {
            val stream = withContext(Dispatchers.IO) { requireContext().contentResolver.openInputStream(uri) }
            if (stream == null) {
                appendLog("Could not open the selected file.")
                setUploading(false)
                return@launch
            }

            val percentCallback = mainThreadPercentCallback()
            val messageCallback = mainThreadMessageCallback()
            val completeCallback: (Boolean) -> Unit = { success ->
                requireActivity().runOnUiThread {
                    setUploading(false)
                    appendLog(if (success) "\u2713 Upload complete" else "\u2717 Upload failed — see the error above")
                    Snackbar.make(
                        binding.root,
                        if (success) R.string.upload_success else R.string.upload_failed,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            when (uploadMode) {
                UploadMode.STANDARD -> usbSerialManager.uploadHex(
                    firmware = stream,
                    board = selectedBoard,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback,
                    completeCallback = completeCallback
                )

                UploadMode.CUSTOM -> {
                    val (preOpen, close) = defaultResetBehaviorFor(selectedProtocol)
                    val arduino = Arduino(
                        model = binding.customBoardName.text?.toString()?.ifBlank { null } ?: "Custom board",
                        mcu = selectedMcu,
                        baudRate = binding.customBaudRate.text?.toString()?.toIntOrNull()
                            ?: defaultBaudRateFor(selectedProtocol),
                        protocol = selectedProtocol,
                        preOpenResetBehavior = preOpen,
                        closeResetBehavior = close
                    )
                    usbSerialManager.uploadHexCustom(
                        firmware = stream,
                        arduino = arduino,
                        percentCallback = percentCallback,
                        messageCallback = messageCallback,
                        completeCallback = completeCallback
                    )
                }

                UploadMode.USBASP -> usbaspManager.uploadHex(
                    firmware = stream,
                    mcu = selectedUsbaspMcu,
                    memoryType = selectedMemoryType,
                    sckOption = selectedSckOption,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback,
                    completeCallback = completeCallback
                )

                UploadMode.ARDUINO_ISP -> arduinoIspManager.uploadHex(
                    firmware = stream,
                    mcu = selectedArduinoIspMcu,
                    memoryType = selectedArduinoIspMemoryType,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback,
                    completeCallback = completeCallback
                )
            }
        }
    }

    private fun startVerify() {
        val uri = selectedHexUri
        if (uri == null) {
            Snackbar.make(binding.root, R.string.error_pick_hex_first, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!isDeviceReady()) {
            Snackbar.make(binding.root, R.string.error_device_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        setUploading(true, getString(R.string.verifying))
        binding.logText.text = ""

        lifecycleScope.launch {
            val stream = withContext(Dispatchers.IO) { requireContext().contentResolver.openInputStream(uri) }
            if (stream == null) {
                appendLog("Could not open the selected file.")
                setUploading(false)
                return@launch
            }

            val percentCallback = mainThreadPercentCallback()
            val messageCallback = mainThreadMessageCallback()
            val completeCallback: (Boolean) -> Unit = { success ->
                requireActivity().runOnUiThread {
                    setUploading(false)
                    appendLog(if (success) "\u2713 Verify complete" else "\u2717 Verify failed — see the error above")
                    Snackbar.make(
                        binding.root,
                        if (success) R.string.verify_success else R.string.verify_failed,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            if (uploadMode == UploadMode.ARDUINO_ISP) {
                arduinoIspManager.verify(
                    firmware = stream,
                    mcu = selectedArduinoIspMcu,
                    memoryType = selectedArduinoIspMemoryType,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback,
                    completeCallback = completeCallback
                )
            } else {
                usbaspManager.verify(
                    firmware = stream,
                    mcu = selectedUsbaspMcu,
                    memoryType = selectedMemoryType,
                    sckOption = selectedSckOption,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback,
                    completeCallback = completeCallback
                )
            }
        }
    }

    private fun startRead() {
        if (!isDeviceReady()) {
            Snackbar.make(binding.root, R.string.error_device_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        setUploading(true, getString(R.string.reading_device))
        binding.logText.text = ""

        lifecycleScope.launch {
            val percentCallback = mainThreadPercentCallback()
            val messageCallback = mainThreadMessageCallback()

            val result = if (uploadMode == UploadMode.ARDUINO_ISP) {
                arduinoIspManager.readMemory(
                    mcu = selectedArduinoIspMcu,
                    memoryType = selectedArduinoIspMemoryType,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback
                )
            } else {
                usbaspManager.readMemory(
                    mcu = selectedUsbaspMcu,
                    memoryType = selectedMemoryType,
                    sckOption = selectedSckOption,
                    percentCallback = percentCallback,
                    messageCallback = messageCallback
                )
            }

            when (result) {
                is UsbaspReadResult.Data -> {
                    // Read succeeded — now ask where to save it. setUploading(false) happens in
                    // onSaveLocationPicked() once that finishes (or is cancelled).
                    pendingReadBytes = result.bytes
                    val safeName = currentIspMcu().displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val modePrefix = if (uploadMode == UploadMode.ARDUINO_ISP) "arduinoisp" else "usbasp"
                    saveReadResult.launch("${modePrefix}_${currentIspMemoryType().label.lowercase()}_$safeName.hex")
                }
                UsbaspReadResult.SingleValueLogged -> {
                    setUploading(false)
                    appendLog("\u2713 Read complete")
                    Snackbar.make(binding.root, R.string.read_success, Snackbar.LENGTH_LONG).show()
                }
                UsbaspReadResult.Failed -> {
                    setUploading(false)
                    appendLog("\u2717 Read failed — see the error above")
                    Snackbar.make(binding.root, R.string.read_failed, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onSaveLocationPicked(uri: Uri?) {
        val bytes = pendingReadBytes
        pendingReadBytes = null

        if (uri == null || bytes == null) {
            setUploading(false)
            return
        }

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(IntelHexWriter.encode(bytes).toByteArray())
                    } ?: throw IllegalStateException("Could not open the destination file")
                }
            }
            setUploading(false)
            saved.onSuccess {
                appendLog(getString(R.string.read_saved_format, queryDisplayName(uri) ?: uri.toString()))
                appendLog("\u2713 Read complete")
                Snackbar.make(binding.root, R.string.read_success, Snackbar.LENGTH_LONG).show()
            }.onFailure { e ->
                appendLog("ERROR: ${e.message ?: e.javaClass.simpleName}")
                Snackbar.make(binding.root, R.string.read_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // endregion

    /**
     * Safety net for leaving this screen mid-operation (view destroyed while
     * uploading - app closed, task killed, ...): lifecycleScope cancels the
     * running upload/read/verify coroutine right away, but that means it
     * never reaches its own completeCallback - the one place
     * setUploading(false) would otherwise run - so without this,
     * FlashForegroundService's AVR owner would stay registered forever and
     * keep the shared notification pinned on. Unlike the old Activity's
     * onDestroy, switching away from the HEX UPLOADER tab does NOT call this
     * - the FragmentContainerView hosting this Fragment is only hidden
     * (View.GONE), never removed, so an upload keeps running in the
     * background if the user switches tabs mid-upload, exactly like it would
     * if they switched to another app instead. This only fires when
     * MainActivity's own view is actually torn down.
     */
    override fun onDestroyView() {
        if (isUploading) {
            FlashForegroundService.stop(requireContext().applicationContext, FlashForegroundService.OWNER_AVR)
        }
        _binding = null
        onConnectionStateChanged?.invoke(false)
        super.onDestroyView()
    }
}
