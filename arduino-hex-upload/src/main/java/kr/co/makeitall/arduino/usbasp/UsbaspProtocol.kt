package kr.co.makeitall.arduino.usbasp

/**
 * USBasp is a raw-USB (control-transfer) AVR ISP programmer — not a USB-serial device, so none
 * of [kr.co.makeitall.arduino.UsbSerialManager]'s felHR85-based machinery applies to it. Every
 * constant below is taken directly from avrdude 8.2's own USBasp driver source
 * (src/usbasp.c, src/usbasp.h, src/usbdevs.h) and its avrdude.conf part definitions, not
 * reconstructed from memory, so it matches real hardware/firmware exactly.
 */
object UsbaspIds {
    // src/usbdevs.h: USBASP_SHARED_VID / USBASP_SHARED_PID (modern firmware, VOTI shared ID)
    const val VENDOR_ID = 0x16C0
    const val PRODUCT_ID = 0x05DC

    // src/usbdevs.h: USBASP_OLD_VID / USBASP_OLD_PID (old/unofficial ATMEL-VID clones)
    const val OLD_VENDOR_ID = 0x03EB
    const val OLD_PRODUCT_ID = 0xC7B4

    fun matches(vendorId: Int, productId: Int): Boolean =
        (vendorId == VENDOR_ID && productId == PRODUCT_ID) ||
            (vendorId == OLD_VENDOR_ID && productId == OLD_PRODUCT_ID)
}

/** src/usbasp.h USB function call identifiers (the control transfer's bRequest field). */
object UsbaspFunction {
    const val CONNECT = 1
    const val DISCONNECT = 2
    const val TRANSMIT = 3
    const val READFLASH = 4
    const val ENABLEPROG = 5
    const val WRITEFLASH = 6
    const val READEEPROM = 7
    const val WRITEEEPROM = 8
    const val SETLONGADDRESS = 9
    const val SETISPSCK = 10
    const val GETCAPABILITIES = 127
}

/**
 * src/usbasp.h block mode flags. NOTE: avrdude 8.2's actual write loop
 * (usbasp_spi_paged_write) only ever sets FIRST on the first chunk and never sets LAST at all —
 * LAST is defined in the header but effectively unused by the modern firmware/driver pairing.
 * Ported to match that exactly rather than "completing" the pattern with a LAST flag that real
 * avrdude doesn't send.
 */
object UsbaspBlockFlag {
    const val FIRST = 1
}

/** src/usbasp.c usbasp_transmit(): block sizes used per USB control-transfer chunk. */
object UsbaspBlockSize {
    const val READ = 200
    const val WRITE = 200
}

/**
 * src/usbasp.h ISP SCK speed identifiers, sent via SETISPSCK. Slower boards/breadboard wiring
 * benefit from a slower SCK; AUTO lets the firmware pick and auto-slow on no response.
 */
enum class UsbaspSckOption(val id: Int, val label: String) {
    AUTO(0, "Auto"),
    HZ_187_5(9, "187.5 kHz"),
    HZ_375(10, "375 kHz"),
    HZ_750(11, "750 kHz"),
    KHZ_1500(12, "1.5 MHz");

    override fun toString() = label
}

/**
 * Which chip memory a Read/Write/Verify operation targets — mirrors the picker in common
 * USBasp flasher apps. FLASH and EEPROM are paged and file-based (read/write/verify all
 * supported). SIGNATURE/CALIBRATION/LOCK/EFUSE/HFUSE/LFUSE are single small values read via a
 * raw 4-byte ISP command — this app supports reading them (for diagnostics) but deliberately
 * does not support writing fuses/lock bits: a wrong fuse write (e.g. clock source, RSTDISBL) can
 * brick a chip until it's recovered with high-voltage programming, which is out of scope for a
 * USB-ISP app. Flash/EEPROM writes are always preceded by a chip erase, which is safe to redo.
 */
enum class UsbaspMemoryType(val label: String, val isPaged: Boolean) {
    FLASH("Flash", true),
    EEPROM("EEPROM", true),
    SIGNATURE("Signature", false),
    CALIBRATION("Calibration", false),
    LOCK("Lock", false),
    EFUSE("Extended Fuse", false),
    HFUSE("High Fuse", false),
    LFUSE("Low Fuse", false);

    override fun toString() = label
}

/**
 * Per-part flash/EEPROM geometry, signature, and chip-erase delay for every classic
 * ISP-capable AVR8 part in avrdude 8.2's avrdude.conf (prog_modes containing PM_ISP, resolved
 * through the full "part parent" inheritance chain — not read off single entries in isolation,
 * since most parts inherit most of their fields). Modern UPDI tinyAVR 0/1/2-series, Xmega (PDI),
 * and TPI-only parts (attiny4/5/9/10 etc.) are excluded — USBasp's SPI/ISP mode doesn't program
 * them. A handful of very old, rarely-used parts whose EEPROM geometry doesn't resolve cleanly
 * through inheritance (m103, m161, m163, m8515, m8535, t26) are also excluded rather than
 * guessed. 169 parts total.
 *
 * id = the avrdude part id (e.g. "m328p"), kept only as a stable key, not shown in the UI.
 */
data class UsbaspMcuInfo(
    val id: String,
    val displayName: String,
    val signature: IntArray,
    val flashSize: Int,
    val flashPageSize: Int,
    val eepromSize: Int,
    val eepromPageSize: Int,
    val chipEraseDelayMs: Long
) {
    override fun toString() = displayName
}

object UsbaspMcuTable {
    val all: List<UsbaspMcuInfo> = listOf(
    UsbaspMcuInfo("c128", "AT90CAN128", intArrayOf(0x1E, 0x97, 0x81), 131072, 256, 4096, 8, 9),
    UsbaspMcuInfo("c32", "AT90CAN32", intArrayOf(0x1E, 0x95, 0x81), 32768, 256, 1024, 8, 9),
    UsbaspMcuInfo("c64", "AT90CAN64", intArrayOf(0x1E, 0x96, 0x81), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("pwm1", "AT90PWM1", intArrayOf(0x1E, 0x93, 0x83), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("pwm161", "AT90PWM161", intArrayOf(0x1E, 0x94, 0x8B), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("pwm2", "AT90PWM2", intArrayOf(0x1E, 0x93, 0x81), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("pwm216", "AT90PWM216", intArrayOf(0x1E, 0x94, 0x83), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("pwm2b", "AT90PWM2B", intArrayOf(0x1E, 0x93, 0x83), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("pwm3", "AT90PWM3", intArrayOf(0x1E, 0x93, 0x81), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("pwm316", "AT90PWM316", intArrayOf(0x1E, 0x94, 0x83), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("pwm3b", "AT90PWM3B", intArrayOf(0x1E, 0x93, 0x83), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("pwm81", "AT90PWM81", intArrayOf(0x1E, 0x93, 0x88), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("usb1286", "AT90USB1286", intArrayOf(0x1E, 0x97, 0x82), 131072, 256, 4096, 8, 9),
    UsbaspMcuInfo("usb1287", "AT90USB1287", intArrayOf(0x1E, 0x97, 0x82), 131072, 256, 4096, 8, 9),
    UsbaspMcuInfo("usb162", "AT90USB162", intArrayOf(0x1E, 0x94, 0x82), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("usb646", "AT90USB646", intArrayOf(0x1E, 0x96, 0x82), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("usb647", "AT90USB647", intArrayOf(0x1E, 0x96, 0x82), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("usb82", "AT90USB82", intArrayOf(0x1E, 0x93, 0x82), 8192, 128, 512, 4, 9),
    UsbaspMcuInfo("a5505", "ATA5505", intArrayOf(0x1E, 0x94, 0x87), 16384, 128, 512, 4, 4),
    UsbaspMcuInfo("a6612c", "ATA6612C", intArrayOf(0x1E, 0x93, 0x0A), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("a6613c", "ATA6613C", intArrayOf(0x1E, 0x94, 0x06), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("a6614q", "ATA6614Q", intArrayOf(0x1E, 0x95, 0x0F), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("a6616c", "ATA6616C", intArrayOf(0x1E, 0x93, 0x87), 8192, 128, 512, 4, 4),
    UsbaspMcuInfo("a6617c", "ATA6617C", intArrayOf(0x1E, 0x94, 0x87), 16384, 128, 512, 4, 4),
    UsbaspMcuInfo("a664251", "ATA664251", intArrayOf(0x1E, 0x94, 0x87), 16384, 128, 512, 4, 4),
    UsbaspMcuInfo("m128", "ATmega128", intArrayOf(0x1E, 0x97, 0x02), 131072, 256, 4096, 8, 10),
    UsbaspMcuInfo("m1280", "ATmega1280", intArrayOf(0x1E, 0x97, 0x03), 131072, 256, 4096, 8, 9),
    UsbaspMcuInfo("m1281", "ATmega1281", intArrayOf(0x1E, 0x97, 0x04), 131072, 256, 4096, 8, 9),
    UsbaspMcuInfo("m1284", "ATmega1284", intArrayOf(0x1E, 0x97, 0x06), 131072, 256, 4096, 8, 55),
    UsbaspMcuInfo("m1284p", "ATmega1284P", intArrayOf(0x1E, 0x97, 0x05), 131072, 256, 4096, 8, 55),
    UsbaspMcuInfo("m1284rfr2", "ATmega1284RFR2", intArrayOf(0x1E, 0xA7, 0x03), 131072, 256, 4096, 8, 18),
    UsbaspMcuInfo("m128a", "ATmega128A", intArrayOf(0x1E, 0x97, 0x02), 131072, 256, 4096, 8, 10),
    UsbaspMcuInfo("m128rfa1", "ATmega128RFA1", intArrayOf(0x1E, 0xA7, 0x01), 131072, 256, 4096, 8, 18),
    UsbaspMcuInfo("m128rfr2", "ATmega128RFR2", intArrayOf(0x1E, 0xA7, 0x02), 131072, 256, 4096, 8, 18),
    UsbaspMcuInfo("m16", "ATmega16", intArrayOf(0x1E, 0x94, 0x03), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m162", "ATmega162", intArrayOf(0x1E, 0x94, 0x04), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m164a", "ATmega164A", intArrayOf(0x1E, 0x94, 0x0F), 16384, 128, 512, 4, 55),
    UsbaspMcuInfo("m164p", "ATmega164P", intArrayOf(0x1E, 0x94, 0x0A), 16384, 128, 512, 4, 55),
    UsbaspMcuInfo("m164pa", "ATmega164PA", intArrayOf(0x1E, 0x94, 0x0A), 16384, 128, 512, 4, 55),
    UsbaspMcuInfo("m165", "ATmega165", intArrayOf(0x1E, 0x94, 0x07), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m165a", "ATmega165A", intArrayOf(0x1E, 0x94, 0x10), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m165p", "ATmega165P", intArrayOf(0x1E, 0x94, 0x07), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m165pa", "ATmega165PA", intArrayOf(0x1E, 0x94, 0x07), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m168", "ATmega168", intArrayOf(0x1E, 0x94, 0x06), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m168a", "ATmega168A", intArrayOf(0x1E, 0x94, 0x06), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m168p", "ATmega168P", intArrayOf(0x1E, 0x94, 0x0B), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m168pa", "ATmega168PA", intArrayOf(0x1E, 0x94, 0x0B), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m168pb", "ATmega168PB", intArrayOf(0x1E, 0x94, 0x15), 16384, 128, 512, 4, 10),
    UsbaspMcuInfo("m169", "ATmega169", intArrayOf(0x1E, 0x94, 0x05), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m169a", "ATmega169A", intArrayOf(0x1E, 0x94, 0x11), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m169p", "ATmega169P", intArrayOf(0x1E, 0x94, 0x05), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m169pa", "ATmega169PA", intArrayOf(0x1E, 0x94, 0x05), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m16a", "ATmega16A", intArrayOf(0x1E, 0x94, 0x03), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m16hva", "ATmega16HVA", intArrayOf(0x1E, 0x94, 0x0C), 16384, 128, 256, 4, 4),
    UsbaspMcuInfo("m16hvb", "ATmega16HVB", intArrayOf(0x1E, 0x94, 0x0D), 16384, 128, 512, 4, 4),
    UsbaspMcuInfo("m16hvbrevb", "ATmega16HVBrevB", intArrayOf(0x1E, 0x94, 0x0D), 16384, 128, 512, 4, 4),
    UsbaspMcuInfo("m16m1", "ATmega16M1", intArrayOf(0x1E, 0x94, 0x84), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m16u2", "ATmega16U2", intArrayOf(0x1E, 0x94, 0x89), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m16u4", "ATmega16U4", intArrayOf(0x1E, 0x94, 0x88), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("m2560", "ATmega2560", intArrayOf(0x1E, 0x98, 0x01), 262144, 256, 4096, 8, 9),
    UsbaspMcuInfo("m2561", "ATmega2561", intArrayOf(0x1E, 0x98, 0x02), 262144, 256, 4096, 8, 9),
    UsbaspMcuInfo("m2564rfr2", "ATmega2564RFR2", intArrayOf(0x1E, 0xA8, 0x03), 262144, 256, 8192, 8, 18),
    UsbaspMcuInfo("m256rfr2", "ATmega256RFR2", intArrayOf(0x1E, 0xA8, 0x02), 262144, 256, 8192, 8, 18),
    UsbaspMcuInfo("m32", "ATmega32", intArrayOf(0x1E, 0x95, 0x02), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m324a", "ATmega324A", intArrayOf(0x1E, 0x95, 0x15), 32768, 128, 1024, 4, 55),
    UsbaspMcuInfo("m324p", "ATmega324P", intArrayOf(0x1E, 0x95, 0x08), 32768, 128, 1024, 4, 55),
    UsbaspMcuInfo("m324pa", "ATmega324PA", intArrayOf(0x1E, 0x95, 0x11), 32768, 128, 1024, 4, 55),
    UsbaspMcuInfo("m324pb", "ATmega324PB", intArrayOf(0x1E, 0x95, 0x17), 32768, 128, 1024, 4, 55),
    UsbaspMcuInfo("m325", "ATmega325", intArrayOf(0x1E, 0x95, 0x05), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3250", "ATmega3250", intArrayOf(0x1E, 0x95, 0x06), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3250a", "ATmega3250A", intArrayOf(0x1E, 0x95, 0x06), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3250p", "ATmega3250P", intArrayOf(0x1E, 0x95, 0x0E), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3250pa", "ATmega3250PA", intArrayOf(0x1E, 0x95, 0x0E), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m325a", "ATmega325A", intArrayOf(0x1E, 0x95, 0x05), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m325p", "ATmega325P", intArrayOf(0x1E, 0x95, 0x0D), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m325pa", "ATmega325PA", intArrayOf(0x1E, 0x95, 0x0D), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m328", "ATmega328", intArrayOf(0x1E, 0x95, 0x14), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m328p", "ATmega328P", intArrayOf(0x1E, 0x95, 0x0F), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m328pb", "ATmega328PB", intArrayOf(0x1E, 0x95, 0x16), 32768, 128, 1024, 4, 10),
    UsbaspMcuInfo("m329", "ATmega329", intArrayOf(0x1E, 0x95, 0x03), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3290", "ATmega3290", intArrayOf(0x1E, 0x95, 0x04), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3290a", "ATmega3290A", intArrayOf(0x1E, 0x95, 0x04), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3290p", "ATmega3290P", intArrayOf(0x1E, 0x95, 0x0C), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m3290pa", "ATmega3290PA", intArrayOf(0x1E, 0x95, 0x0C), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m329a", "ATmega329A", intArrayOf(0x1E, 0x95, 0x03), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m329p", "ATmega329P", intArrayOf(0x1E, 0x95, 0x0B), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m329pa", "ATmega329PA", intArrayOf(0x1E, 0x95, 0x0B), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m32a", "ATmega32A", intArrayOf(0x1E, 0x95, 0x02), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m32c1", "ATmega32C1", intArrayOf(0x1E, 0x95, 0x86), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m32hvb", "ATmega32HVB", intArrayOf(0x1E, 0x95, 0x10), 32768, 128, 1024, 4, 4),
    UsbaspMcuInfo("m32hvbrevb", "ATmega32HVBrevB", intArrayOf(0x1E, 0x95, 0x10), 32768, 128, 1024, 4, 4),
    UsbaspMcuInfo("m32hve2", "ATmega32HVE2", intArrayOf(0x1E, 0x95, 0x13), 32768, 128, 1024, 4, 4),
    UsbaspMcuInfo("m32m1", "ATmega32M1", intArrayOf(0x1E, 0x95, 0x84), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m32u2", "ATmega32U2", intArrayOf(0x1E, 0x95, 0x8A), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m32u4", "ATmega32U4", intArrayOf(0x1E, 0x95, 0x87), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("m48", "ATmega48", intArrayOf(0x1E, 0x92, 0x05), 4096, 64, 256, 4, 45),
    UsbaspMcuInfo("m48a", "ATmega48A", intArrayOf(0x1E, 0x92, 0x05), 4096, 64, 256, 4, 45),
    UsbaspMcuInfo("m48p", "ATmega48P", intArrayOf(0x1E, 0x92, 0x0A), 4096, 64, 256, 4, 45),
    UsbaspMcuInfo("m48pa", "ATmega48PA", intArrayOf(0x1E, 0x92, 0x0A), 4096, 64, 256, 4, 45),
    UsbaspMcuInfo("m48pb", "ATmega48PB", intArrayOf(0x1E, 0x92, 0x10), 4096, 64, 256, 4, 10),
    UsbaspMcuInfo("m64", "ATmega64", intArrayOf(0x1E, 0x96, 0x02), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m640", "ATmega640", intArrayOf(0x1E, 0x96, 0x08), 65536, 256, 4096, 8, 9),
    UsbaspMcuInfo("m644", "ATmega644", intArrayOf(0x1E, 0x96, 0x09), 65536, 256, 2048, 8, 55),
    UsbaspMcuInfo("m644a", "ATmega644A", intArrayOf(0x1E, 0x96, 0x09), 65536, 256, 2048, 8, 55),
    UsbaspMcuInfo("m644p", "ATmega644P", intArrayOf(0x1E, 0x96, 0x0A), 65536, 256, 2048, 8, 55),
    UsbaspMcuInfo("m644pa", "ATmega644PA", intArrayOf(0x1E, 0x96, 0x0A), 65536, 256, 2048, 8, 55),
    UsbaspMcuInfo("m644rfr2", "ATmega644RFR2", intArrayOf(0x1E, 0xA6, 0x03), 65536, 256, 2048, 8, 18),
    UsbaspMcuInfo("m645", "ATmega645", intArrayOf(0x1E, 0x96, 0x05), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6450", "ATmega6450", intArrayOf(0x1E, 0x96, 0x06), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6450a", "ATmega6450A", intArrayOf(0x1E, 0x96, 0x06), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6450p", "ATmega6450P", intArrayOf(0x1E, 0x96, 0x0E), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m645a", "ATmega645A", intArrayOf(0x1E, 0x96, 0x05), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m645p", "ATmega645P", intArrayOf(0x1E, 0x96, 0x0D), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m649", "ATmega649", intArrayOf(0x1E, 0x96, 0x03), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6490", "ATmega6490", intArrayOf(0x1E, 0x96, 0x04), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6490a", "ATmega6490A", intArrayOf(0x1E, 0x96, 0x04), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m6490p", "ATmega6490P", intArrayOf(0x1E, 0x96, 0x0C), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m649a", "ATmega649A", intArrayOf(0x1E, 0x96, 0x03), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m649p", "ATmega649P", intArrayOf(0x1E, 0x96, 0x0B), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m64a", "ATmega64A", intArrayOf(0x1E, 0x96, 0x02), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m64c1", "ATmega64C1", intArrayOf(0x1E, 0x96, 0x86), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m64hve2", "ATmega64HVE2", intArrayOf(0x1E, 0x96, 0x10), 65536, 128, 1024, 4, 4),
    UsbaspMcuInfo("m64m1", "ATmega64M1", intArrayOf(0x1E, 0x96, 0x84), 65536, 256, 2048, 8, 9),
    UsbaspMcuInfo("m64rfr2", "ATmega64RFR2", intArrayOf(0x1E, 0xA6, 0x02), 65536, 256, 2048, 8, 18),
    UsbaspMcuInfo("m8", "ATmega8", intArrayOf(0x1E, 0x93, 0x07), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m88", "ATmega88", intArrayOf(0x1E, 0x93, 0x0A), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m88a", "ATmega88A", intArrayOf(0x1E, 0x93, 0x0A), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m88p", "ATmega88P", intArrayOf(0x1E, 0x93, 0x0F), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m88pa", "ATmega88PA", intArrayOf(0x1E, 0x93, 0x0F), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m88pb", "ATmega88PB", intArrayOf(0x1E, 0x93, 0x16), 8192, 64, 512, 4, 10),
    UsbaspMcuInfo("m8a", "ATmega8A", intArrayOf(0x1E, 0x93, 0x07), 8192, 64, 512, 4, 9),
    UsbaspMcuInfo("m8hva", "ATmega8HVA", intArrayOf(0x1E, 0x93, 0x10), 8192, 128, 256, 4, 4),
    UsbaspMcuInfo("m8u2", "ATmega8U2", intArrayOf(0x1E, 0x93, 0x89), 8192, 128, 512, 4, 9),
    UsbaspMcuInfo("ms128", "ATmegaS128", intArrayOf(0x1E, 0x97, 0x02), 131072, 256, 4096, 8, 10),
    UsbaspMcuInfo("ms64m1", "ATmegaS64M1", intArrayOf(0x1E, 0x96, 0x84), 65536, 256, 2048, 8, 10),
    UsbaspMcuInfo("t13", "ATtiny13", intArrayOf(0x1E, 0x90, 0x07), 1024, 32, 64, 4, 4),
    UsbaspMcuInfo("t13a", "ATtiny13A", intArrayOf(0x1E, 0x90, 0x07), 1024, 32, 64, 4, 4),
    UsbaspMcuInfo("t1634", "ATtiny1634", intArrayOf(0x1E, 0x94, 0x12), 16384, 32, 256, 4, 9),
    UsbaspMcuInfo("t1634r", "ATtiny1634R", intArrayOf(0x1E, 0x94, 0x12), 16384, 32, 256, 4, 9),
    UsbaspMcuInfo("t167", "ATtiny167", intArrayOf(0x1E, 0x94, 0x87), 16384, 128, 512, 4, 15),
    UsbaspMcuInfo("t2313", "ATtiny2313", intArrayOf(0x1E, 0x91, 0x0A), 2048, 32, 128, 4, 9),
    UsbaspMcuInfo("t2313a", "ATtiny2313A", intArrayOf(0x1E, 0x91, 0x0A), 2048, 32, 128, 4, 9),
    UsbaspMcuInfo("t24", "ATtiny24", intArrayOf(0x1E, 0x91, 0x0B), 2048, 32, 128, 4, 4),
    UsbaspMcuInfo("t24a", "ATtiny24A", intArrayOf(0x1E, 0x91, 0x0B), 2048, 32, 128, 4, 4),
    UsbaspMcuInfo("t25", "ATtiny25", intArrayOf(0x1E, 0x91, 0x08), 2048, 32, 128, 4, 4),
    UsbaspMcuInfo("t261", "ATtiny261", intArrayOf(0x1E, 0x91, 0x0C), 2048, 32, 128, 4, 4),
    UsbaspMcuInfo("t261a", "ATtiny261A", intArrayOf(0x1E, 0x91, 0x0C), 2048, 32, 128, 4, 4),
    UsbaspMcuInfo("t4313", "ATtiny4313", intArrayOf(0x1E, 0x92, 0x0D), 4096, 64, 256, 4, 9),
    UsbaspMcuInfo("t43u", "ATtiny43U", intArrayOf(0x1E, 0x92, 0x0C), 4096, 64, 64, 4, 1),
    UsbaspMcuInfo("t44", "ATtiny44", intArrayOf(0x1E, 0x92, 0x07), 4096, 64, 256, 4, 4),
    UsbaspMcuInfo("t441", "ATtiny441", intArrayOf(0x1E, 0x92, 0x15), 4096, 16, 256, 4, 4),
    UsbaspMcuInfo("t44a", "ATtiny44A", intArrayOf(0x1E, 0x92, 0x07), 4096, 64, 256, 4, 4),
    UsbaspMcuInfo("t45", "ATtiny45", intArrayOf(0x1E, 0x92, 0x06), 4096, 64, 256, 4, 4),
    UsbaspMcuInfo("t461", "ATtiny461", intArrayOf(0x1E, 0x92, 0x08), 4096, 64, 256, 4, 4),
    UsbaspMcuInfo("t461a", "ATtiny461A", intArrayOf(0x1E, 0x92, 0x08), 4096, 64, 256, 4, 4),
    UsbaspMcuInfo("t48", "ATtiny48", intArrayOf(0x1E, 0x92, 0x09), 4096, 64, 64, 4, 15),
    UsbaspMcuInfo("t828", "ATtiny828", intArrayOf(0x1E, 0x93, 0x14), 8192, 64, 256, 4, 15),
    UsbaspMcuInfo("t828r", "ATtiny828R", intArrayOf(0x1E, 0x93, 0x14), 8192, 64, 256, 4, 15),
    UsbaspMcuInfo("t84", "ATtiny84", intArrayOf(0x1E, 0x93, 0x0C), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("t841", "ATtiny841", intArrayOf(0x1E, 0x93, 0x15), 8192, 16, 512, 4, 4),
    UsbaspMcuInfo("t84a", "ATtiny84A", intArrayOf(0x1E, 0x93, 0x0C), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("t85", "ATtiny85", intArrayOf(0x1E, 0x93, 0x0B), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("t861", "ATtiny861", intArrayOf(0x1E, 0x93, 0x0D), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("t861a", "ATtiny861A", intArrayOf(0x1E, 0x93, 0x0D), 8192, 64, 512, 4, 4),
    UsbaspMcuInfo("t87", "ATtiny87", intArrayOf(0x1E, 0x93, 0x87), 8192, 128, 512, 4, 15),
    UsbaspMcuInfo("t88", "ATtiny88", intArrayOf(0x1E, 0x93, 0x11), 8192, 64, 64, 4, 9),
    UsbaspMcuInfo("lgt168p", "LGT8F168P", intArrayOf(0x1E, 0x94, 0x0B), 16384, 128, 512, 4, 9),
    UsbaspMcuInfo("lgt328p", "LGT8F328P", intArrayOf(0x1E, 0x95, 0x0F), 32768, 128, 1024, 4, 9),
    UsbaspMcuInfo("lgt88p", "LGT8F88P", intArrayOf(0x1E, 0x93, 0x0F), 8192, 64, 512, 4, 9),
    )

    /** Default selection for the picker — the board this app already knows best. */
    val default: UsbaspMcuInfo = all.first { it.id == "m328p" }
}
