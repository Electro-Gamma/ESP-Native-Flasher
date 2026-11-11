package io.espflasher.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.espflasher.app.protocol.ReadChunkEvent
import io.espflasher.app.protocol.SerialTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single open USB-UART connection. Extends the protocol layer's
 * [SerialTransport] so an [io.espflasher.app.protocol.EspRomLoader] can talk
 * to it directly, plus the lifecycle/configuration calls the UI needs.
 */
interface UsbSerialPort : SerialTransport {
    val info: UsbDeviceInfo
    var config: SerialConfig

    /** Claims the interface, applies [config], and asserts the idle line state. */
    suspend fun open()

    /** Releases the interface and closes the underlying connection. */
    suspend fun close()

    /** Re-applies baud/data/stop/parity/flow-control without closing the port. */
    suspend fun applyConfig(newConfig: SerialConfig)
}

/**
 * Shared bulk-endpoint plumbing (a background read pump feeding buffered
 * reads, chunked writes, DTR/RTS bookkeeping) so each chip driver only has
 * to implement the vendor-specific control transfers in [applyConfig] and
 * [setDtrRts].
 */
abstract class AbstractUsbSerialPort(
    protected val connection: UsbDeviceConnection,
    protected val usbInterface: UsbInterface,
    protected val endpointIn: UsbEndpoint,
    protected val endpointOut: UsbEndpoint,
    override val info: UsbDeviceInfo,
    initialConfig: SerialConfig
) : UsbSerialPort {

    override var config: SerialConfig = initialConfig

    // ---------------------------------------------------------------------
    // Read side: a background "pump" continuously drains the IN endpoint
    // into this channel, independent of whether/when anyone is actually
    // asking for data. readByteOrNull/readBytes just consume from it.
    //
    // This pump runs on its OWN dedicated Thread, deliberately NOT as a
    // coroutine on Dispatchers.IO. The first version of this fix used a
    // coroutine there, which fixed the problem completely at 115200 baud
    // but not at 921600 - and that split is the tell: at 921600 baud
    // (~92160 B/s) a typical USB-UART bridge chip's own onboard RX FIFO
    // (often only a few hundred bytes on chips like the CP210x family)
    // fills in low single-digit milliseconds, whereas at 115200 baud
    // (~11520 B/s) the same FIFO buys roughly 8x that margin. Dispatchers.IO
    // is a *shared* thread pool - if this loop's coroutine ever got
    // preempted by another coroutine queued on that same pool (and a read
    // operation is also driving constant progress/log UI updates that can
    // dispatch through IO), the resulting gap was small enough to be
    // completely harmless at 115200 baud but large enough to overflow the
    // hardware FIFO at 921600. A dedicated, elevated-priority Thread can't
    // be preempted by other coroutines sharing a pool, because it isn't in
    // that pool at all - normal OS thread scheduling still applies, but
    // that's a much smaller and more predictable risk than pool contention.
    // ---------------------------------------------------------------------
    private val incomingChunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var curChunk: ByteArray = ByteArray(0)
    private var curChunkPos: Int = 0
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private var pumpThread: Thread? = null
    @Volatile private var pumpRunning = false

    // Diagnostic-only record of recent chunk timing (see
    // SerialTransport.recentReadChunks's doc comment). A lock-free deque
    // since the pump thread writes to it on every single successful
    // bulkTransfer - it must never contend with anything, that's the
    // entire point of this file's last two fixes.
    private val recentChunks = java.util.concurrent.ConcurrentLinkedDeque<ReadChunkEvent>()

    override fun recentReadChunks(): List<ReadChunkEvent> = recentChunks.toList()

    @Volatile protected var dtrAsserted = false
    @Volatile protected var rtsAsserted = false
    @Volatile private var opened = false

    override val isOpen: Boolean get() = opened

    protected fun markOpen() {
        opened = true
        startReadPump()
    }

    /**
     * Stops the read pump and waits for its thread to actually finish
     * before returning, so a driver's `close()` can safely call
     * `connection.close()` right after this without racing the pump's own
     * in-flight `bulkTransfer()` call on a different thread. The join
     * itself is a blocking call, so it's pushed onto [Dispatchers.IO]
     * rather than blocking whatever thread is running this suspend
     * function.
     */
    protected suspend fun markClosed() {
        pumpRunning = false
        val thread = pumpThread
        pumpThread = null
        if (thread != null) {
            withContext(Dispatchers.IO) { thread.join(PUMP_JOIN_TIMEOUT_MS) }
        }
        readMutex.withLock { clearReadState() }
        opened = false
    }

    /**
     * Continuously issues `bulkTransfer()` reads on the IN endpoint and
     * pushes whatever comes back into [incomingChunks], as fast as the
     * device can send it - completely decoupled from whether
     * [readByteOrNull]/[readBytes] happen to be waiting right now. See the
     * class-level doc comment above [incomingChunks] for why this runs on
     * a dedicated [Thread] rather than a coroutine.
     *
     * A `bulkTransfer()` timeout (no data within [PUMP_POLL_TIMEOUT_MS])
     * just means "nothing to forward this iteration" - not a failure - so
     * the loop simply polls again immediately. Any actual exception (e.g.
     * the connection getting closed out from under it) ends the pump
     * quietly; subsequent reads then just time out waiting on the channel,
     * consistent with how a failed on-demand read used to surface as well.
     */
    private fun startReadPump() {
        pumpRunning = true
        val thread = Thread({
            val buf = ByteArray(READ_BUFFER_SIZE)
            while (pumpRunning) {
                val n = try {
                    connection.bulkTransfer(endpointIn, buf, buf.size, PUMP_POLL_TIMEOUT_MS)
                } catch (e: Throwable) {
                    break
                }
                if (n > 0) {
                    recentChunks.addLast(ReadChunkEvent(System.currentTimeMillis(), n))
                    while (recentChunks.size > MAX_RECENT_CHUNKS) recentChunks.pollFirst()
                    incomingChunks.trySend(buf.copyOf(n))
                }
            }
        }, "UsbSerialReadPump")
        thread.isDaemon = true
        thread.priority = Thread.MAX_PRIORITY
        pumpThread = thread
        thread.start()
    }

    private fun clearReadState() {
        curChunk = ByteArray(0)
        curChunkPos = 0
        recentChunks.clear()
        while (true) {
            val result = incomingChunks.tryReceive()
            if (!result.isSuccess) break
        }
    }

    override suspend fun write(data: ByteArray): Unit = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            var offset = 0
            while (offset < data.size) {
                val len = minOf(MAX_WRITE_CHUNK, data.size - offset)
                val chunk = if (offset == 0 && len == data.size) data else data.copyOfRange(offset, offset + len)
                writeChunkFully(chunk, len)
                offset += len
            }
        }
    }

    /**
     * Submits [len] bytes of [chunk] to the OUT endpoint, resubmitting
     * whatever's left over if a single `bulkTransfer()` call returns a
     * short write.
     *
     * `bulkTransfer()` is built directly on the Linux `USBDEVFS_BULK`
     * ioctl, which documents returning the *actual* number of bytes
     * transferred - possibly less than requested - as a normal outcome
     * (a timeout partway through, the device momentarily not draining its
     * endpoint fast enough), not just as a failure signal. The previous
     * version of this function only checked `sent < 0` and otherwise
     * assumed `sent == len`, silently dropping whatever was left over on
     * a partial return. That corrupts the SLIP-framed byte stream from
     * that point on with no error raised at all - the ROM/stub then either
     * rejects the truncated packet's checksum or never sees its closing
     * 0xC0, and the caller's response read times out. Because whether any
     * given transfer comes up short is down to USB host-controller/OS
     * scheduling rather than packet content, this is exactly the kind of
     * bug that shows up as a write stalling at an unpredictable point -
     * more likely the longer a transfer runs (more `bulkTransfer` calls,
     * more chances to hit it), and at a different point every time.
     */
    private fun writeChunkFully(chunk: ByteArray, len: Int) {
        var sentSoFar = 0
        var zeroProgressStreak = 0
        while (sentSoFar < len) {
            val remaining = len - sentSoFar
            val buf = if (sentSoFar == 0) chunk else chunk.copyOfRange(sentSoFar, sentSoFar + remaining)
            val sent = connection.bulkTransfer(endpointOut, buf, remaining, WRITE_TIMEOUT_MS)
            if (sent < 0) throw io.espflasher.app.protocol.FlasherException.UsbDisconnected()
            if (sent == 0) {
                // No error, but no progress either - can legitimately happen
                // once (device momentarily busy). Retry a bounded number of
                // times rather than either spinning forever or (the old bug)
                // silently treating zero-progress as if it were complete.
                zeroProgressStreak++
                if (zeroProgressStreak >= MAX_ZERO_PROGRESS_RETRIES) {
                    throw io.espflasher.app.protocol.FlasherException.UsbDisconnected()
                }
                continue
            }
            zeroProgressStreak = 0
            sentSoFar += sent
        }
    }

    override suspend fun readByteOrNull(timeoutMs: Long): Int? = readMutex.withLock {
        if (curChunkPos >= curChunk.size) {
            val next = withTimeoutOrNull(timeoutMs) { incomingChunks.receive() } ?: return@withLock null
            curChunk = next
            curChunkPos = 0
        }
        val b = curChunk[curChunkPos].toInt() and 0xFF
        curChunkPos++
        b
    }

    /**
     * See [SerialTransport.readBytes]'s doc comment for why this exists
     * alongside [readByteOrNull]. Waits (up to [timeoutMs]) for at least
     * one chunk the background pump has already pulled off the wire, then
     * opportunistically drains any further chunks already sitting in the
     * channel (without blocking for them) to fill [buf] as much as
     * possible in one call.
     */
    override suspend fun readBytes(buf: ByteArray, timeoutMs: Long): Int = readMutex.withLock {
        if (curChunkPos >= curChunk.size) {
            val next = withTimeoutOrNull(timeoutMs) { incomingChunks.receive() } ?: return@withLock 0
            curChunk = next
            curChunkPos = 0
        }
        var written = 0
        while (written < buf.size) {
            if (curChunkPos >= curChunk.size) {
                val more = incomingChunks.tryReceive().getOrNull() ?: break
                curChunk = more
                curChunkPos = 0
            }
            val available = curChunk.size - curChunkPos
            val toCopy = minOf(available, buf.size - written)
            System.arraycopy(curChunk, curChunkPos, buf, written, toCopy)
            curChunkPos += toCopy
            written += toCopy
        }
        written
    }

    override suspend fun purgeInput(): Unit = readMutex.withLock {
        clearReadState()
    }

    companion object {
        const val READ_BUFFER_SIZE = 4096
        const val MAX_WRITE_CHUNK = 4096
        const val WRITE_TIMEOUT_MS = 3000
        const val MAX_ZERO_PROGRESS_RETRIES = 5

        /**
         * Per-poll timeout for the background read pump thread's
         * `bulkTransfer()` calls. Long enough that the pump isn't
         * needlessly spinning the CPU when idle (a call returns as soon as
         * data arrives, well before this elapses, so this only matters
         * when nothing is coming), short enough that the pump notices
         * `pumpRunning = false` and exits reasonably promptly on close.
         */
        const val PUMP_POLL_TIMEOUT_MS = 1000

        /** How long [AbstractUsbSerialPort.markClosed] waits for the pump
         * thread to actually exit before giving up on the join. */
        const val PUMP_JOIN_TIMEOUT_MS = 2000L

        /** How many recent chunk-timing entries [recentReadChunks] keeps.
         * A 4096-byte sector at the pump's read buffer size arrives in at
         * most a handful of chunks in the healthy case, so this comfortably
         * covers several sectors' worth of history without holding onto
         * much memory. */
        const val MAX_RECENT_CHUNKS = 64
    }
}
