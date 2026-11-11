package kr.co.makeitall.arduino.usbasp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.makeitall.arduino.LineReader
import IntelHexFormatReader.HexFileReader
import IntelHexFormatReader.Model.MemoryBlock
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader

/**
 * High-level USBasp support: finds a connected USBasp, handles its (Android 14+ safe)
 * permission request, and drives Upload (write), Verify, and Read against Flash or EEPROM, plus
 * read-only access to Signature/Calibration/Lock/Fuses. Mirrors the percent/message/complete
 * callback shape used by [kr.co.makeitall.arduino.UsbSerialManager] so it plugs into the same
 * UI code paths, but is otherwise fully independent — USBasp is a raw-USB device, not a serial
 * one, so none of the felHR85 machinery is involved.
 *
 * Fuse/lock *writing* is intentionally not implemented: a wrong write to those (clock source,
 * RSTDISBL, etc.) can brick a chip until it's recovered with high-voltage programming, which
 * this app has no way to do. Reading them is fully supported for diagnostics.
 */
class UsbaspManager(private val context: Context, lifecycle: Lifecycle) : DefaultLifecycleObserver {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set

    private var onChangeListener: (() -> Unit)? = null

    fun addOnChangeListener(listener: () -> Unit): UsbaspManager {
        onChangeListener = listener
        return this
    }

    fun hasPermission(): Boolean = device?.let { usbManager.hasPermission(it) } ?: false

    fun requestPermission() {
        val target = device ?: return
        // Same Android 14+ requirement as UsbSerialManager: a mutable PendingIntent must wrap
        // an EXPLICIT intent (scoped to our own package), or the OS refuses to create it.
        val intent = Intent(ACTION_USBASP_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(target, pendingIntent)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USBASP_PERMISSION, UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refresh()
                    onChangeListener?.invoke()
                }
            }
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_USBASP_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        refresh()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — nothing to do.
        }
    }

    /** Re-scans connected USB devices for a USBasp match. */
    fun refresh() {
        device = usbManager.deviceList.values.firstOrNull { UsbaspProgrammer.isUsbasp(it) }
    }

    private fun IntArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    /** Opens, connects, enables programming, and checks the signature. Shared by every action. */
    private fun UsbaspProgrammer.beginSession(mcu: UsbaspMcuInfo, sckOption: UsbaspSckOption, messageCallback: (String) -> Unit) {
        if (!open()) throw UsbaspException("Could not open the USBasp — try reconnecting it")
        messageCallback("Connecting to USBasp...")
        setSck(sckOption)
        connect()
        enableProgramming()

        messageCallback("Checking device signature...")
        val signature = readSignature()
        if (!signature.contentEquals(mcu.signature)) {
            throw UsbaspException(
                "Signature mismatch: expected ${mcu.signature.toHexString()}, got ${signature.toHexString()} " +
                    "— check the selected MCU and the wiring (MOSI/MISO/SCK/RESET/VCC/GND)"
            )
        }
        messageCallback("Device signature checked: ${signature.toHexString()}")
    }

    /**
     * Upload (write): parses the hex file and writes it to Flash or EEPROM, then reads it back
     * to verify. Any other [memoryType] is refused (fuse/lock writes are unsupported by design
     * — see the class doc). Flash writes are preceded by a full chip erase; EEPROM ISP writes
     * auto-erase each byte as they go, so no separate erase step applies there.
     */
    suspend fun uploadHex(
        hexCode: String? = null,
        firmware: InputStream? = null,
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
        sckOption: UsbaspSckOption,
        percentCallback: (percent: Int) -> Unit,
        messageCallback: (message: String) -> Unit,
        completeCallback: (complete: Boolean) -> Unit
    ) {
        if (memoryType != UsbaspMemoryType.FLASH && memoryType != UsbaspMemoryType.EEPROM) {
            messageCallback(
                "ERROR: Writing ${memoryType.label} isn't supported by this app — a wrong fuse/lock " +
                    "write can brick a chip, so those are read-only here. Use Read to check the current value."
            )
            completeCallback(false)
            return
        }

        val target = device
        if (target == null) {
            messageCallback("ERROR: No USBasp device found")
            completeCallback(false)
            return
        }

        val programmer = UsbaspProgrammer(context, target)

        try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting USBasp uploader...")

                val maxSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashSize else mcu.eepromSize
                val pageSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashPageSize else mcu.eepromPageSize

                val lines = LineReader(
                    hexCode?.let { StringReader(it) } ?: firmware?.let { InputStreamReader(it) }
                        ?: throw UsbaspException("code and firmware is null")
                ).readLines()
                val block: MemoryBlock = HexFileReader(lines, maxSize).Parse()
                val usedLength = block.highestModifiedOffset + 1
                if (usedLength > maxSize) {
                    throw UsbaspException("Program is larger than the target's ${memoryType.label.lowercase()} ($maxSize bytes available)")
                }
                val rawImage = ByteArray(usedLength) { i -> block.cells[i].value }

                try {
                    programmer.beginSession(mcu, sckOption, messageCallback)

                    if (memoryType == UsbaspMemoryType.FLASH) {
                        messageCallback("Erasing chip...")
                        programmer.chipErase(mcu.chipEraseDelayMs)
                    }

                    // USBasp's firmware only commits a page once the incoming byte stream
                    // crosses that page's boundary — a final partial page that stops short of
                    // the boundary is loaded into the chip's page buffer but never actually
                    // written. Pad up to the next full page so every page, including the last,
                    // triggers a commit.
                    val paddedLength = ((usedLength + pageSize - 1) / pageSize) * pageSize
                    val image = when {
                        paddedLength == usedLength -> rawImage
                        memoryType == UsbaspMemoryType.FLASH ->
                            // Just erased, so erased flash already reads as 0xFF — padding with
                            // 0xFF is a safe no-op against blank silicon.
                            ByteArray(paddedLength) { i -> if (i < usedLength) rawImage[i] else 0xFF.toByte() }
                        else -> {
                            // EEPROM isn't necessarily erased (depends on the EESAVE fuse) and
                            // may hold unrelated data past the write range — pad with what's
                            // actually there instead of overwriting it with 0xFF.
                            messageCallback("Reading existing EEPROM to avoid overwriting bytes past the write range...")
                            val existing = programmer.readEeprom(paddedLength)
                            ByteArray(paddedLength) { i -> if (i < usedLength) rawImage[i] else existing[i] }
                        }
                    }

                    messageCallback("Writing $usedLength bytes to ${memoryType.label.lowercase()} (page size $pageSize)...")
                    if (memoryType == UsbaspMemoryType.FLASH) {
                        programmer.writeFlash(image, pageSize) { percent -> percentCallback(percent / 2) }
                    } else {
                        programmer.writeEeprom(image, pageSize) { percent -> percentCallback(percent / 2) }
                    }
                    messageCallback("$usedLength bytes written to ${memoryType.label.lowercase()}!")

                    messageCallback("Verifying program...")
                    val readBack = if (memoryType == UsbaspMemoryType.FLASH) {
                        programmer.readFlash(usedLength) { percent -> percentCallback(50 + percent / 2) }
                    } else {
                        programmer.readEeprom(usedLength) { percent -> percentCallback(50 + percent / 2) }
                    }
                    if (!readBack.contentEquals(rawImage)) {
                        val mismatchAt = readBack.indices.firstOrNull { readBack[it] != rawImage[it] }
                        throw UsbaspException("Verification failed — mismatch at byte offset ${mismatchAt ?: -1}")
                    }
                    messageCallback("$usedLength bytes verified!")
                    messageCallback("Verified program!")
                } finally {
                    programmer.close()
                }
            }

            percentCallback(100)
            completeCallback(true)
        } catch (e: Exception) {
            e.printStackTrace()
            messageCallback("ERROR: ${e.message ?: e.javaClass.simpleName}")
            completeCallback(false)
        }
    }

    /**
     * Verify: reads back Flash or EEPROM and compares against the given hex file, without
     * erasing or writing anything. Any other [memoryType] is refused — there's no loaded value
     * to compare a fuse/lock/signature byte against in this app.
     */
    suspend fun verify(
        hexCode: String? = null,
        firmware: InputStream? = null,
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
        sckOption: UsbaspSckOption,
        percentCallback: (percent: Int) -> Unit,
        messageCallback: (message: String) -> Unit,
        completeCallback: (complete: Boolean) -> Unit
    ) {
        if (memoryType != UsbaspMemoryType.FLASH && memoryType != UsbaspMemoryType.EEPROM) {
            messageCallback("Verify only applies to Flash/EEPROM — use Read to check ${memoryType.label}.")
            completeCallback(false)
            return
        }

        val target = device
        if (target == null) {
            messageCallback("ERROR: No USBasp device found")
            completeCallback(false)
            return
        }

        val programmer = UsbaspProgrammer(context, target)

        try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting USBasp verify...")

                val maxSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashSize else mcu.eepromSize
                val lines = LineReader(
                    hexCode?.let { StringReader(it) } ?: firmware?.let { InputStreamReader(it) }
                        ?: throw UsbaspException("code and firmware is null")
                ).readLines()
                val block: MemoryBlock = HexFileReader(lines, maxSize).Parse()
                val usedLength = block.highestModifiedOffset + 1
                val image = ByteArray(usedLength) { i -> block.cells[i].value }

                try {
                    programmer.beginSession(mcu, sckOption, messageCallback)

                    messageCallback("Reading back $usedLength bytes of ${memoryType.label.lowercase()}...")
                    val readBack = if (memoryType == UsbaspMemoryType.FLASH) {
                        programmer.readFlash(usedLength) { percent -> percentCallback(percent) }
                    } else {
                        programmer.readEeprom(usedLength) { percent -> percentCallback(percent) }
                    }
                    if (!readBack.contentEquals(image)) {
                        val mismatchAt = readBack.indices.firstOrNull { readBack[it] != image[it] }
                        throw UsbaspException("Verification failed — mismatch at byte offset ${mismatchAt ?: -1}")
                    }
                    messageCallback("$usedLength bytes verified!")
                } finally {
                    programmer.close()
                }
            }

            percentCallback(100)
            completeCallback(true)
        } catch (e: Exception) {
            e.printStackTrace()
            messageCallback("ERROR: ${e.message ?: e.javaClass.simpleName}")
            completeCallback(false)
        }
    }

    /**
     * Read: for Flash/EEPROM, reads the whole memory and returns the raw bytes so the caller
     * can encode them with [IntelHexWriter] and save to a file. For Signature/Calibration/Lock/
     * *Fuse, logs a formatted value via [messageCallback] instead — there's nothing to save for
     * a single byte. [UsbaspReadResult] distinguishes "succeeded, nothing to save" from
     * "failed" — both would otherwise collapse to a null ByteArray.
     */
    suspend fun readMemory(
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
        sckOption: UsbaspSckOption,
        percentCallback: (percent: Int) -> Unit,
        messageCallback: (message: String) -> Unit
    ): UsbaspReadResult {
        val target = device
        if (target == null) {
            messageCallback("ERROR: No USBasp device found")
            return UsbaspReadResult.Failed
        }

        val programmer = UsbaspProgrammer(context, target)

        return try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting USBasp read...")
                try {
                    programmer.beginSession(mcu, sckOption, messageCallback)

                    when (memoryType) {
                        UsbaspMemoryType.FLASH -> {
                            messageCallback("Reading ${mcu.flashSize} bytes of flash...")
                            UsbaspReadResult.Data(programmer.readFlash(mcu.flashSize) { percentCallback(it) })
                        }
                        UsbaspMemoryType.EEPROM -> {
                            messageCallback("Reading ${mcu.eepromSize} bytes of EEPROM...")
                            UsbaspReadResult.Data(programmer.readEeprom(mcu.eepromSize) { percentCallback(it) })
                        }
                        UsbaspMemoryType.SIGNATURE -> {
                            // beginSession already read + logged it.
                            UsbaspReadResult.SingleValueLogged
                        }
                        UsbaspMemoryType.CALIBRATION -> {
                            messageCallback("Calibration byte: 0x%02X".format(programmer.readCalibration()))
                            UsbaspReadResult.SingleValueLogged
                        }
                        UsbaspMemoryType.LOCK -> {
                            messageCallback("Lock byte: 0x%02X".format(programmer.readLock()))
                            UsbaspReadResult.SingleValueLogged
                        }
                        UsbaspMemoryType.LFUSE -> {
                            messageCallback("Low fuse: 0x%02X".format(programmer.readLFuse()))
                            UsbaspReadResult.SingleValueLogged
                        }
                        UsbaspMemoryType.HFUSE -> {
                            messageCallback("High fuse: 0x%02X".format(programmer.readHFuse()))
                            UsbaspReadResult.SingleValueLogged
                        }
                        UsbaspMemoryType.EFUSE -> {
                            messageCallback("Extended fuse: 0x%02X".format(programmer.readEFuse()))
                            UsbaspReadResult.SingleValueLogged
                        }
                    }
                } finally {
                    programmer.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            messageCallback("ERROR: ${e.message ?: e.javaClass.simpleName}")
            UsbaspReadResult.Failed
        }
    }

    companion object {
        const val ACTION_USBASP_PERMISSION = "kr.co.makeitall.arduino.usbasp.action.USB_PERMISSION_REQUEST"
    }
}

/** Result of [UsbaspManager.readMemory] — distinguishes "nothing to save" from "failed". */
sealed class UsbaspReadResult {
    data class Data(val bytes: ByteArray) : UsbaspReadResult()
    object SingleValueLogged : UsbaspReadResult()
    object Failed : UsbaspReadResult()
}
