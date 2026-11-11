package io.espflasher.app.flashing

/** Console line severity - drives both icon/color in the UI and save-log formatting. */
enum class LogLevel { COMMAND, RESPONSE, INFO, SUCCESS, WARNING, ERROR, DEBUG }

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * How [FlashOperationManager] reports back to whoever is driving it, without
 * depending on Android UI classes or StateFlow directly - keeps the business
 * logic layer testable and reusable outside of a ViewModel.
 */
interface FlashCallback {
    fun onLog(level: LogLevel, message: String)
    fun onProgress(progress: ProgressState)
}
