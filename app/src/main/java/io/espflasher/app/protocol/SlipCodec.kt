package io.espflasher.app.protocol

import java.io.ByteArrayOutputStream

/**
 * SLIP (Serial Line Internet Protocol, RFC 1055) framing as used by the ESP ROM
 * and stub bootloaders to delimit request/response packets over the UART link.
 *
 * Frame format:  0xC0 <escaped payload> 0xC0
 * Escaping:      0xC0 -> 0xDB 0xDC
 *                0xDB -> 0xDB 0xDD
 *
 * This is a from-scratch implementation of the publicly specified SLIP framing;
 * it does not call into or embed any third-party flashing tool.
 */
object SlipCodec {

    private const val END = 0xC0
    private const val ESC = 0xDB
    private const val ESC_END = 0xDC
    private const val ESC_ESC = 0xDD

    /** Wraps [payload] in a complete SLIP frame, ready to write to the serial port. */
    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(END)
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                END -> {
                    out.write(ESC)
                    out.write(ESC_END)
                }
                ESC -> {
                    out.write(ESC)
                    out.write(ESC_ESC)
                }
                else -> out.write(b.toInt())
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    /**
     * Incremental SLIP frame reassembler. Feed raw bytes as they arrive from the
     * serial port via [feed]; a complete decoded frame is returned each time one
     * is finished, or null if more bytes are still needed.
     */
    class Reader {
        private val buffer = ByteArrayOutputStream()
        private var started = false
        private var escaping = false

        /** Feed one raw byte from the wire. Returns the decoded payload if a frame just completed. */
        fun feed(byte: Int): ByteArray? {
            val b = byte and 0xFF
            if (!started) {
                if (b == END) started = true
                return null
            }
            // Checked before the END/ESC cases below: a byte arriving while
            // mid-escape-sequence is *always* the second half of that escape,
            // full stop - even if its raw value happens to equal END (0xC0)
            // or ESC (0xDB). A well-formed sender's escape second-byte is
            // always ESC_END/ESC_ESC and never literally END, so this branch
            // firing on anything else already means the two sides have
            // diverged; passing it through (the else arm below) is the
            // correct defensive move, not treating it as a frame boundary.
            if (escaping) {
                escaping = false
                when (b) {
                    ESC_END -> buffer.write(END)
                    ESC_ESC -> buffer.write(ESC)
                    else -> buffer.write(b) // malformed escape - pass through defensively
                }
                return null
            }
            if (b == END) {
                // Some ROMs send a leading/trailing extra 0xC0; ignore empty frames.
                if (buffer.size() == 0) return null
                val frame = buffer.toByteArray()
                buffer.reset()
                started = false
                escaping = false
                return frame
            }
            if (b == ESC) {
                escaping = true
                return null
            }
            buffer.write(b)
            return null
        }

        fun reset() {
            buffer.reset()
            started = false
            escaping = false
        }
    }
}
