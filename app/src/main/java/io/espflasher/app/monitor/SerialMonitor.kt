package io.espflasher.app.monitor

import android.hardware.usb.UsbDevice
import io.espflasher.app.flashing.LogLevel
import io.espflasher.app.protocol.ResetSequences
import io.espflasher.app.usb.SerialConfig
import io.espflasher.app.usb.UsbSerialManager
import io.espflasher.app.usb.UsbSerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** What the Send button appends to the message, same four choices as the Arduino IDE. */
enum class LineEnding(val suffix: String) {
    NONE(""), NEWLINE("\n"), CARRIAGE_RETURN("\r"), BOTH("\r\n")
}

data class MonitorLine(val id: Long, val timeMs: Long, val text: String)

data class MonitorState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val statusText: String = "",
    val deviceLabel: String = "",
    val baud: Int = 115200,
    val lineEnding: LineEnding = LineEnding.NEWLINE,
    val autoscroll: Boolean = true,
    val showTimestamp: Boolean = false,
    /** Completed lines plus (last) the line still being received. */
    val lines: List<MonitorLine> = emptyList(),
    val rxBytes: Long = 0
)

/**
 * Arduino-IDE-style serial monitor.
 *
 * It reads whatever the board prints and can send text back. It uses the app's already-open
 * Connect port when there is one (ESP modes), otherwise it opens the selected USB-serial device
 * itself (e.g. an Arduino board). It is stopped before any flash/erase/connect operation (see
 * MainViewModel#runOperation) so it never fights the flasher for the port, and when it shared
 * the Connect port it puts the port's original serial settings back.
 */
class SerialMonitor(
    private val scope: CoroutineScope,
    private val usbSerialManager: UsbSerialManager,
    private val sharedPort: () -> UsbSerialPort?,
    private val log: (LogLevel, String) -> Unit,
) {
    companion object {
        val BAUD_RATES = listOf(
            300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880,
            115200, 230400, 250000, 460800, 500000, 921600, 1000000, 2000000
        )
        private const val MAX_LINES = 5000
        private const val MAX_LINE_CHARS = 2000
        private const val PUBLISH_INTERVAL_MS = 60L
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.running || _state.value.starting

    private var job: Job? = null
    private var activePort: UsbSerialPort? = null
    private var ownedPort: UsbSerialPort? = null
    private var ownedDeviceName: String? = null
    private var restoreConfig: SerialConfig? = null

    // Line assembly - only touched from the read coroutine.
    private val completed = ArrayDeque<MonitorLine>()
    private val partial = StringBuilder()
    private var partialTime = 0L
    private var nextId = 1L
    private var rx = 0L

    // ------------------------------------------------------------------ settings

    fun setBaud(baud: Int) {
        _state.update { it.copy(baud = baud) }
        val port = activePort
        if (_state.value.running && port != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { port.setBaudRate(baud) }
                    .onFailure { log(LogLevel.ERROR, "Serial monitor: could not change baud rate: ${it.message}") }
            }
        }
    }

    fun setLineEnding(value: LineEnding) = _state.update { it.copy(lineEnding = value) }
    fun setAutoscroll(value: Boolean) = _state.update { it.copy(autoscroll = value) }
    fun setShowTimestamp(value: Boolean) = _state.update { it.copy(showTimestamp = value) }

    @Synchronized
    fun clear() {
        completed.clear()
        partial.setLength(0)
        _state.update { it.copy(lines = emptyList()) }
    }

    fun asText(): String = _state.value.lines.joinToString("\n") { it.text }

    // ------------------------------------------------------------------ lifecycle

    /** [device] is only used when there is no open Connect port to share. */
    fun start(device: () -> UsbDevice?, deviceLabel: String) {
        if (isActive) return
        _state.update { it.copy(starting = true, statusText = "") }
        job = scope.launch(Dispatchers.IO) {
            try {
                val shared = sharedPort()?.takeIf { it.isOpen }
                val port: UsbSerialPort
                if (shared != null) {
                    port = shared
                    restoreConfig = shared.config
                    shared.applyConfig(shared.config.copy(baudRate = _state.value.baud))
                } else {
                    val usb = device() ?: throw IllegalStateException("No USB device selected or it is no longer attached.")
                    if (!usbSerialManager.hasPermission(usb)) {
                        if (!usbSerialManager.requestPermission(usb)) throw IllegalStateException("USB permission denied.")
                    }
                    port = usbSerialManager.buildPort(usb, SerialConfig(baudRate = _state.value.baud))
                    port.open()
                    ownedPort = port
                    ownedDeviceName = usb.deviceName
                }
                activePort = port
                val label = shared?.info?.displayLabel ?: deviceLabel
                _state.update { it.copy(starting = false, running = true, deviceLabel = label) }
                log(LogLevel.INFO, "Serial monitor started at ${_state.value.baud} baud")
                readLoop(port)
                if (!port.isOpen) log(LogLevel.WARNING, "Serial monitor: device disconnected.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Serial monitor: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable) { release() }
            }
        }
    }

    /** Stops reading and gives the port back. Safe to call when not running. */
    suspend fun stopAndJoin() {
        val j = job ?: return
        if (j.isActive) {
            val wasRunning = _state.value.running
            j.cancelAndJoin()
            if (wasRunning) log(LogLevel.INFO, "Serial monitor stopped")
        }
    }

    fun stop(): Job = scope.launch { stopAndJoin() }

    /** Called when the attached-device list changes: stop if the device we opened ourselves was unplugged. */
    fun onDevicesChanged(attachedDeviceNames: Collection<String>) {
        val owned = ownedDeviceName ?: return
        if (isActive && owned !in attachedDeviceNames) stop()
    }

    private suspend fun release() {
        val owned = ownedPort
        val restore = restoreConfig
        val shared = activePort
        runCatching {
            if (owned != null) {
                owned.close()
            } else if (shared != null && restore != null && shared.isOpen) {
                shared.applyConfig(restore)
            }
            Unit
        }
        ownedPort = null
        ownedDeviceName = null
        restoreConfig = null
        activePort = null
        _state.update { it.copy(starting = false, running = false) }
    }

    // ------------------------------------------------------------------ TX

    /** Returns false if the monitor isn't running. */
    fun send(text: String): Boolean {
        val port = activePort
        if (!_state.value.running || port == null) return false
        val payload = (text + _state.value.lineEnding.suffix).toByteArray(Charsets.UTF_8)
        if (payload.isEmpty()) return true
        scope.launch(Dispatchers.IO) {
            runCatching { port.write(payload) }
                .onFailure { log(LogLevel.ERROR, "Serial monitor: send failed: ${it.message}") }
        }
        return true
    }

    /** Pulses EN/RESET through RTS - reboots a typical ESP dev board so its boot log shows up. */
    fun resetBoard(): Boolean {
        val port = activePort
        if (!_state.value.running || port == null) return false
        scope.launch(Dispatchers.IO) {
            runCatching { ResetSequences.resetToRun(port) }
                .onFailure { log(LogLevel.ERROR, "Serial monitor: reset failed: ${it.message}") }
        }
        return true
    }

    // ------------------------------------------------------------------ RX

    private suspend fun readLoop(port: UsbSerialPort) {
        val buf = ByteArray(4096)
        val decoder = Utf8Stream()
        var dirty = false
        var lastPublish = 0L
        while (port.isOpen) {
            currentCoroutineContext().ensureActive()
            val n = port.readBytes(buf, 50)
            if (n > 0) {
                rx += n
                appendText(decoder.decode(buf, n))
                dirty = true
            }
            val now = System.currentTimeMillis()
            if (dirty && now - lastPublish >= PUBLISH_INTERVAL_MS) {
                publish()
                lastPublish = now
                dirty = false
            }
        }
        if (dirty) publish()
    }

    @Synchronized
    private fun appendText(text: CharSequence) {
        for (ch in text) {
            when {
                ch == '\n' -> finishLine()
                ch == '\r' -> Unit
                ch == '\t' || ch >= ' ' -> {
                    if (partial.isEmpty()) partialTime = System.currentTimeMillis()
                    partial.append(ch)
                    if (partial.length >= MAX_LINE_CHARS) finishLine()
                }
                else -> Unit // other control characters (bell, NUL, ...) are not shown
            }
        }
    }

    private fun finishLine() {
        val time = if (partial.isEmpty()) System.currentTimeMillis() else partialTime
        completed.addLast(MonitorLine(nextId++, time, ANSI.replace(partial.toString(), "")))
        partial.setLength(0)
        while (completed.size > MAX_LINES) completed.removeFirst()
    }

    @Synchronized
    private fun publish() {
        val out = ArrayList<MonitorLine>(completed.size + 1)
        out.addAll(completed)
        if (partial.isNotEmpty()) out.add(MonitorLine(nextId, partialTime, ANSI.replace(partial.toString(), "")))
        _state.update { it.copy(lines = out, rxBytes = rx) }
    }

    /** Incremental UTF-8 decoder: a multi-byte character split across two USB reads is kept, not mangled. */
    private class Utf8Stream {
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private var carry = ByteArray(0)

        fun decode(data: ByteArray, len: Int): CharSequence {
            val all = ByteArray(carry.size + len)
            System.arraycopy(carry, 0, all, 0, carry.size)
            System.arraycopy(data, 0, all, carry.size, len)
            val inBuf = ByteBuffer.wrap(all)
            val outBuf = CharBuffer.allocate(all.size + 4)
            decoder.reset()
            decoder.decode(inBuf, outBuf, false)
            carry = ByteArray(inBuf.remaining()).also { inBuf.get(it) }
            outBuf.flip()
            return outBuf
        }
    }
}
