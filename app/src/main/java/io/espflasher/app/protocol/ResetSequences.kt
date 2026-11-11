package io.espflasher.app.protocol

import kotlinx.coroutines.delay

/**
 * Classic Espressif auto-reset circuit sequences, driven purely over DTR/RTS.
 * This is the same two-line dance every ESP dev board with a CP2102/CH340
 * auto-program circuit expects: RTS drives EN/RESET, DTR drives IO0/GPIO0
 * (inverted through a transistor on most boards, hence the logic-level
 * inversions below).
 */
object ResetSequences {

    /**
     * Reset the chip and hold GPIO0 low so it comes up in the UART download
     * bootloader instead of running the flashed application.
     */
    suspend fun enterBootloader(transport: SerialTransport) {
        transport.setDtrRts(dtr = false, rts = true) // EN=0 (reset asserted)
        delay(100)
        transport.setDtrRts(dtr = true, rts = false) // IO0=0, EN=1 (reset released, IO0 held low)
        delay(50)
        transport.setDtrRts(dtr = true, rts = false) // keep IO0 low while chip boots
        delay(50)
        transport.setDtrRts(dtr = false, rts = false) // release IO0, chip is now in the bootloader
    }

    /** Toggle EN to reboot into the normal, flashed application. */
    suspend fun resetToRun(transport: SerialTransport) {
        transport.setDtrRts(dtr = false, rts = true)
        delay(100)
        transport.setDtrRts(dtr = false, rts = false)
        delay(50)
    }

    /**
     * Alternate sequence for USB-CDC boards (native USB on S2/S3/C3/C6/H2)
     * where a light "1200bps touch"-less reset via RTS alone is more reliable
     * because there's no separate USB-UART bridge chip translating the lines.
     */
    suspend fun enterBootloaderUsbCdc(transport: SerialTransport) {
        transport.setDtrRts(dtr = true, rts = true)
        delay(100)
        transport.setDtrRts(dtr = true, rts = false)
        delay(100)
        transport.setDtrRts(dtr = false, rts = false)
    }
}
