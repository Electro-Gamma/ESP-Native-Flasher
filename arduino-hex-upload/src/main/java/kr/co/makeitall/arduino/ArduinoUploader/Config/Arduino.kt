package kr.co.makeitall.arduino.ArduinoUploader.Config

import kr.co.makeitall.arduino.Boards

data class Arduino(
    var model: String,
    var mcu: McuIdentifier,
    var baudRate: Int,
    var protocol: Protocol,
    var preOpenResetBehavior: String? = null,
    var postOpenResetBehavior: String? = null,
    var closeResetBehavior: String? = null,
    var sleepAfterOpen: Int = if (protocol == Protocol.Avr109) 0 else 250,
    var readTimeout: Int = 1000,
    var writeTimeout: Int = 1000
) {
    constructor(board: Boards) : this(
        model = board.boardName,
        mcu = board.chipType,
        baudRate = board.uploadBaudRate,
        protocol = board.uploadProtocol,
        // NOTE: these three were previously dropped here (always null), which meant Standard-mode
        // uploads never toggled DTR/RTS to reset the board into its bootloader even though the
        // Boards enum defines the correct behavior per-board. Wiring them through fixes that.
        preOpenResetBehavior = board.openReset.ifBlank { null },
        postOpenResetBehavior = board.postReset.ifBlank { null },
        closeResetBehavior = board.closeReset.ifBlank { null }
    )
}
