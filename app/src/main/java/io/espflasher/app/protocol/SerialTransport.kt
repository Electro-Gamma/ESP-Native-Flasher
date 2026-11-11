package io.espflasher.app.protocol

/**
 * The minimal serial operations the ESP protocol layer needs. Implemented by
 * [io.espflasher.app.usb.UsbSerialPort] over the real USB Host API, which
 * keeps this file (and everything in the `protocol` package) free of any
 * Android USB imports - it could just as easily run against a mock or a
 * TCP socket in tests.
 */
interface SerialTransport {
    /** True while the underlying connection is open and usable. */
    val isOpen: Boolean

    /** Write raw bytes to the wire. */
    suspend fun write(data: ByteArray)

    /**
     * Read one raw byte from the wire, waiting up to [timeoutMs].
     * Returns null on timeout (not an error - callers decide what that means).
     */
    suspend fun readByteOrNull(timeoutMs: Long): Int?

    /**
     * Read whatever's available into [buf] (up to [buf.size] bytes) in a
     * single operation, waiting up to [timeoutMs] for at least one byte.
     * Returns the number of bytes read (1..buf.size), or 0 on timeout (not
     * an error).
     *
     * This exists specifically so a caller reassembling a large multi-byte
     * frame (e.g. [EspRomLoader]'s stub-based flash read, up to 4096 bytes
     * per sector) doesn't pay [readByteOrNull]'s per-byte synchronization
     * cost once per byte of a burst that can be tens of thousands of bytes
     * long. On real hardware that cost isn't just theoretical: it can leave
     * the USB endpoint undrained for long enough, between individual byte
     * reads, that the device-side UART FIFO overflows and silently drops
     * bytes mid-frame - which then shows up as an apparently well-formed
     * but truncated SLIP frame (a real 0xC0 terminator arrives early because
     * a dropped byte desynced the escape-sequence state), not as a timeout.
     * See [io.espflasher.app.usb.AbstractUsbSerialPort]'s implementation.
     */
    suspend fun readBytes(buf: ByteArray, timeoutMs: Long): Int

    /** Change the UART baud rate on the fly (used after CHANGE_BAUDRATE). */
    suspend fun setBaudRate(baud: Int)

    /** Drive DTR/RTS lines directly, used for bootloader-entry reset sequences. */
    suspend fun setDtrRts(dtr: Boolean, rts: Boolean)

    /** Discard any buffered but unread input. */
    suspend fun purgeInput()

    /**
     * A snapshot of the most recent raw reads off the wire (timestamp +
     * byte count only, not the data itself), oldest first. Purely
     * diagnostic: lets a "corrupt sector" error report real chunk timing
     * (per section 17/18 of the investigation write-up this was added
     * for) instead of guessing at what the transport was doing. Default
     * implementation returns nothing, so this is safe for any transport
     * that doesn't track it (or a future non-USB one, e.g. TCP) without
     * needing to override anything.
     */
    fun recentReadChunks(): List<ReadChunkEvent> = emptyList()
}

/** One entry in [SerialTransport.recentReadChunks]: a raw chunk the
 * transport pulled off the wire, and when. */
data class ReadChunkEvent(val timestampMs: Long, val size: Int)
