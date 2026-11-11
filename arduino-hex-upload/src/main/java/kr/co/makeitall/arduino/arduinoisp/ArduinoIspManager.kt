package kr.co.makeitall.arduino.arduinoisp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.makeitall.arduino.LineReader
import kr.co.makeitall.arduino.UsbSerialManager
import kr.co.makeitall.arduino.usbasp.UsbaspMcuInfo
import kr.co.makeitall.arduino.usbasp.UsbaspMemoryType
import kr.co.makeitall.arduino.usbasp.UsbaspReadResult
import IntelHexFormatReader.HexFileReader
import IntelHexFormatReader.Model.MemoryBlock
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader

/**
 * High-level "Arduino as ISP" support: uses a normal Arduino board (running the ArduinoISP
 * sketch, connected exactly like Standard/Custom mode boards — same CH340/FTDI/CDC detection via
 * [UsbSerialManager]) as an SPI/ISP programmer for a *different*, externally-wired target chip.
 * Mirrors [kr.co.makeitall.arduino.usbasp.UsbaspManager]'s Upload/Verify/Read API shape — and
 * reuses its MCU table and memory-type model directly, since both drive the same set of classic
 * AVR8 parts — so this plugs into the same UI code paths.
 *
 * Fuse/lock *writing* is intentionally not implemented, for the same reason as USBasp: a wrong
 * write to those (clock source, RSTDISBL, etc.) can brick a chip until it's recovered with
 * high-voltage programming, which this app has no way to do. Reading them is fully supported.
 */
class ArduinoIspManager(private val context: Context, private val usbSerialManager: UsbSerialManager) {

    private fun IntArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    private fun openProgrammer(): ArduinoIspProgrammer {
        val portName = usbSerialManager.usbDevice?.deviceName
            ?: throw ArduinoIspException("No USB device found")
        usbSerialManager.stopRead()
        val programmer = ArduinoIspProgrammer(context, portName)
        programmer.open()
        return programmer
    }

    private fun closeProgrammer(programmer: ArduinoIspProgrammer) {
        try {
            programmer.close()
        } catch (_: Exception) {
            // Best-effort
        }
        usbSerialManager.startRead(2000)
    }

    /** Opens, syncs, sets device params, enables programming, and checks the signature. */
    private fun ArduinoIspProgrammer.beginSession(mcu: UsbaspMcuInfo, messageCallback: (String) -> Unit) {
        messageCallback("Connecting to ArduinoISP...")
        getSync()
        runCatching {
            val major = getParameter(ArduinoIspProgrammer.PARM_SW_MAJOR)
            val minor = getParameter(ArduinoIspProgrammer.PARM_SW_MINOR)
            messageCallback("Retrieved software version: $major.$minor.")
        }
        setDevice(mcu)
        enterProgramMode()

        messageCallback("Checking device signature...")
        val signature = readSignature()
        if (!signature.contentEquals(mcu.signature)) {
            throw ArduinoIspException(
                "Signature mismatch: expected ${mcu.signature.toHexString()}, got ${signature.toHexString()} " +
                    "— check the selected MCU and the wiring (MOSI/MISO/SCK/RESET/VCC/GND) to the target chip"
            )
        }
        messageCallback("Device signature checked: ${signature.toHexString()}")
    }

    /**
     * Upload (write): parses the hex file and writes it to Flash or EEPROM, then reads it back
     * to verify. Any other [memoryType] is refused. Flash writes are preceded by a full chip
     * erase; EEPROM ISP writes auto-erase each byte as they go, so no separate erase applies.
     */
    suspend fun uploadHex(
        hexCode: String? = null,
        firmware: InputStream? = null,
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
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

        try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting ArduinoISP uploader...")

                val maxSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashSize else mcu.eepromSize
                val pageSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashPageSize else mcu.eepromPageSize
                val memChar = if (memoryType == UsbaspMemoryType.FLASH) 'F' else 'E'

                val lines = LineReader(
                    hexCode?.let { StringReader(it) } ?: firmware?.let { InputStreamReader(it) }
                        ?: throw ArduinoIspException("code and firmware is null")
                ).readLines()
                val block: MemoryBlock = HexFileReader(lines, maxSize).Parse()
                val usedLength = block.highestModifiedOffset + 1
                if (usedLength > maxSize) {
                    throw ArduinoIspException("Program is larger than the target's ${memoryType.label.lowercase()} ($maxSize bytes available)")
                }
                val image = ByteArray(usedLength) { i -> block.cells[i].value }

                val programmer = openProgrammer()
                try {
                    programmer.beginSession(mcu, messageCallback)

                    if (memoryType == UsbaspMemoryType.FLASH) {
                        messageCallback("Erasing chip...")
                        programmer.chipErase(mcu.chipEraseDelayMs)
                    }

                    messageCallback("Writing $usedLength bytes to ${memoryType.label.lowercase()} (page size $pageSize)...")
                    programmer.writeMemory(memChar, image, pageSize) { percent -> percentCallback(percent / 2) }
                    messageCallback("$usedLength bytes written to ${memoryType.label.lowercase()}!")

                    messageCallback("Verifying program...")
                    val readBack = programmer.readMemory(memChar, usedLength, pageSize) { percent -> percentCallback(50 + percent / 2) }
                    if (!readBack.contentEquals(image)) {
                        val mismatchAt = readBack.indices.firstOrNull { readBack[it] != image[it] }
                        throw ArduinoIspException("Verification failed — mismatch at byte offset ${mismatchAt ?: -1}")
                    }
                    messageCallback("$usedLength bytes verified!")
                    messageCallback("Verified program!")
                } finally {
                    closeProgrammer(programmer)
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
     * erasing or writing anything. Any other [memoryType] is refused.
     */
    suspend fun verify(
        hexCode: String? = null,
        firmware: InputStream? = null,
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
        percentCallback: (percent: Int) -> Unit,
        messageCallback: (message: String) -> Unit,
        completeCallback: (complete: Boolean) -> Unit
    ) {
        if (memoryType != UsbaspMemoryType.FLASH && memoryType != UsbaspMemoryType.EEPROM) {
            messageCallback("Verify only applies to Flash/EEPROM — use Read to check ${memoryType.label}.")
            completeCallback(false)
            return
        }

        try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting ArduinoISP verify...")

                val maxSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashSize else mcu.eepromSize
                val pageSize = if (memoryType == UsbaspMemoryType.FLASH) mcu.flashPageSize else mcu.eepromPageSize
                val memChar = if (memoryType == UsbaspMemoryType.FLASH) 'F' else 'E'

                val lines = LineReader(
                    hexCode?.let { StringReader(it) } ?: firmware?.let { InputStreamReader(it) }
                        ?: throw ArduinoIspException("code and firmware is null")
                ).readLines()
                val block: MemoryBlock = HexFileReader(lines, maxSize).Parse()
                val usedLength = block.highestModifiedOffset + 1
                val image = ByteArray(usedLength) { i -> block.cells[i].value }

                val programmer = openProgrammer()
                try {
                    programmer.beginSession(mcu, messageCallback)

                    messageCallback("Reading back $usedLength bytes of ${memoryType.label.lowercase()}...")
                    val readBack = programmer.readMemory(memChar, usedLength, pageSize) { percent -> percentCallback(percent) }
                    if (!readBack.contentEquals(image)) {
                        val mismatchAt = readBack.indices.firstOrNull { readBack[it] != image[it] }
                        throw ArduinoIspException("Verification failed — mismatch at byte offset ${mismatchAt ?: -1}")
                    }
                    messageCallback("$usedLength bytes verified!")
                } finally {
                    closeProgrammer(programmer)
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
     * can encode them with IntelHexWriter and save to a file. For Signature/Calibration/Lock/
     * *Fuse, logs a formatted value via [messageCallback] instead — there's nothing to save for
     * a single byte.
     */
    suspend fun readMemory(
        mcu: UsbaspMcuInfo,
        memoryType: UsbaspMemoryType,
        percentCallback: (percent: Int) -> Unit,
        messageCallback: (message: String) -> Unit
    ): UsbaspReadResult {
        return try {
            withContext(Dispatchers.IO) {
                messageCallback("Starting ArduinoISP read...")
                val programmer = openProgrammer()
                try {
                    programmer.beginSession(mcu, messageCallback)

                    when (memoryType) {
                        UsbaspMemoryType.FLASH -> {
                            messageCallback("Reading ${mcu.flashSize} bytes of flash...")
                            UsbaspReadResult.Data(
                                programmer.readMemory('F', mcu.flashSize, mcu.flashPageSize) { percentCallback(it) }
                            )
                        }
                        UsbaspMemoryType.EEPROM -> {
                            messageCallback("Reading ${mcu.eepromSize} bytes of EEPROM...")
                            UsbaspReadResult.Data(
                                programmer.readMemory('E', mcu.eepromSize, mcu.eepromPageSize) { percentCallback(it) }
                            )
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
                    closeProgrammer(programmer)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            messageCallback("ERROR: ${e.message ?: e.javaClass.simpleName}")
            UsbaspReadResult.Failed
        }
    }
}
