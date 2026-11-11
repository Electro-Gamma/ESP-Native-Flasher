package io.espflasher.app.flashing

/** Snapshot of an in-flight operation, enough to drive the whole Progress panel. */
data class ProgressState(
    val operationLabel: String = "Idle",
    val currentAddress: Long = 0L,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long = 0L,
    val isRunning: Boolean = false
) {
    val percent: Int get() = if (bytesTotal <= 0) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)

    companion object {
        val IDLE = ProgressState()
    }
}

/** Tracks bytes-done over time to produce a smoothed transfer-speed and ETA estimate. */
class ProgressTracker(private val totalBytes: Long, private val operationLabel: String) {
    private val startMs = System.currentTimeMillis()
    private var lastEmitMs = startMs
    private var lastBytes = 0L

    fun update(bytesDone: Long, currentAddress: Long): ProgressState {
        val now = System.currentTimeMillis()
        val elapsedTotalSec = (now - startMs) / 1000.0
        val speed = if (elapsedTotalSec > 0.05) bytesDone / elapsedTotalSec else 0.0
        val remaining = (totalBytes - bytesDone).coerceAtLeast(0)
        val eta = if (speed > 0) (remaining / speed).toLong() else 0L
        lastEmitMs = now
        lastBytes = bytesDone
        return ProgressState(
            operationLabel = operationLabel,
            currentAddress = currentAddress,
            bytesDone = bytesDone,
            bytesTotal = totalBytes,
            speedBytesPerSec = speed,
            etaSeconds = eta,
            isRunning = true
        )
    }
}
