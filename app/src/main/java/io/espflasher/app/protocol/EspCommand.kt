package io.espflasher.app.protocol

/**
 * Opcodes for the Espressif serial ROM-loader / stub-loader protocol.
 *
 * These values are publicly documented as part of Espressif's serial protocol
 * (see docs.espressif.com -> "Serial Protocol" under the esptool documentation)
 * and are simply the wire-format command IDs - they are facts about the
 * hardware/firmware interface, not source code. This file is an original,
 * from-scratch Kotlin implementation of the protocol layer; no esptool code
 * is embedded or invoked.
 */
object EspCommand {
    const val FLASH_BEGIN: Int = 0x02
    const val FLASH_DATA: Int = 0x03
    const val FLASH_END: Int = 0x04
    const val MEM_BEGIN: Int = 0x05
    const val MEM_END: Int = 0x06
    const val MEM_DATA: Int = 0x07
    const val SYNC: Int = 0x08
    const val WRITE_REG: Int = 0x09
    const val READ_REG: Int = 0x0A

    // ESP32-and-later ROM (or ESP8266 stub)
    const val SPI_SET_PARAMS: Int = 0x0B
    const val SPI_ATTACH: Int = 0x0D
    const val READ_FLASH_SLOW: Int = 0x0E // ROM-only slow flash read
    const val CHANGE_BAUDRATE: Int = 0x0F
    const val FLASH_DEFL_BEGIN: Int = 0x10
    const val FLASH_DEFL_DATA: Int = 0x11
    const val FLASH_DEFL_END: Int = 0x12
    const val SPI_FLASH_MD5: Int = 0x13

    // ESP32-S2-and-later ROM only
    const val GET_SECURITY_INFO: Int = 0x14

    // Stub-loader-only extensions (not available before a stub is uploaded)
    const val ERASE_FLASH: Int = 0xD0
    const val ERASE_REGION: Int = 0xD1
    const val READ_FLASH: Int = 0xD2
    const val RUN_USER_CODE: Int = 0xD3

    /** Sent by the ROM when it doesn't understand the requested opcode. */
    const val ROM_INVALID_RECV_MSG: Int = 0x05

    /** Sync payload: 0x07 0x07 0x12 0x20 followed by 32 bytes of 0x55. */
    val SYNC_PAYLOAD: ByteArray = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }

    /** Magic byte marking the start of a valid firmware image. */
    const val IMAGE_MAGIC: Int = 0xE9

    /** Initial XOR-checksum seed used for FLASH_DATA / MEM_DATA payload checksums. */
    const val CHECKSUM_MAGIC: Int = 0xEF

    /** Register holding the per-chip magic value used for chip auto-detection. */
    const val CHIP_DETECT_MAGIC_REG_ADDR: Long = 0x40001000L

    /** Maximum block size for RAM (stub) uploads. */
    const val RAM_BLOCK_SIZE: Int = 0x1800

    /** ROM-loader flash write block size. */
    const val FLASH_WRITE_SIZE: Int = 0x400

    /** Default ROM bootloader UART speed before any baud-rate change. */
    const val ROM_BAUD: Int = 115200
}
