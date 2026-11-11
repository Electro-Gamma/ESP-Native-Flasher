package kr.co.makeitall.arduino.usbasp

/**
 * Encodes raw bytes as an Intel HEX file (the format [IntelHexFormatReader.HexFileReader]
 * reads). Used for USBasp's "Read" action, which dumps a chip's flash/EEPROM back to a .hex
 * file the user picks a save location for. Standard format: colon, byte count, address,
 * record type, data bytes, checksum (`:LLAAAATTDD...CC`), plus a trailing EOF record;
 * addresses beyond 64KB (e.g. ATmega2560's 256KB flash) use Extended
 * Linear Address (type 04) records, exactly as the format spec requires.
 */
object IntelHexWriter {

    fun encode(data: ByteArray, bytesPerLine: Int = 16): String {
        val sb = StringBuilder()
        var lastUpper = -1
        var offset = 0

        while (offset < data.size) {
            val address = offset
            val upper = (address ushr 16) and 0xFFFF
            if (upper != lastUpper) {
                sb.append(extendedLinearAddressRecord(upper)).append('\n')
                lastUpper = upper
            }
            val lineLength = minOf(bytesPerLine, data.size - offset)
            sb.append(dataRecord(address and 0xFFFF, data, offset, lineLength)).append('\n')
            offset += lineLength
        }

        sb.append(":00000001FF")
        return sb.toString()
    }

    private fun dataRecord(address16: Int, data: ByteArray, offset: Int, length: Int): String {
        val bytes = ArrayList<Int>(length + 4)
        bytes.add(length)
        bytes.add((address16 shr 8) and 0xFF)
        bytes.add(address16 and 0xFF)
        bytes.add(0x00) // record type: data
        for (i in 0 until length) bytes.add(data[offset + i].toInt() and 0xFF)
        return renderRecord(bytes)
    }

    private fun extendedLinearAddressRecord(upper16: Int): String {
        val bytes = listOf(0x02, 0x00, 0x00, 0x04, (upper16 shr 8) and 0xFF, upper16 and 0xFF)
        return renderRecord(bytes)
    }

    private fun renderRecord(bytes: List<Int>): String {
        val sum = bytes.sum() and 0xFF
        val checksum = (0x100 - sum) and 0xFF
        val hex = StringBuilder(":")
        for (b in bytes) hex.append("%02X".format(b))
        hex.append("%02X".format(checksum))
        return hex.toString()
    }
}
