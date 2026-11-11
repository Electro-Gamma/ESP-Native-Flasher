package io.espflasher.app.protocol

/**
 * ## ROM-only vs. stub mode
 *
 * esptool-style tools normally upload a small second-stage program ("the stub")
 * into chip RAM over [EspRomLoader.memBegin]/[EspRomLoader.memBlock] before doing
 * any real work, because the permanent ROM bootloader is slower and, on most
 * chips, cannot read flash back or bulk-erase it.
 *
 * This app supports both paths:
 *
 *  - **Stub mode** ([EspressifStubLoader], the "StubLoader" checkbox, on by
 *    default): uploads Espressif's separately published, dual-licensed
 *    (Apache-2.0 / MIT) flasher-stub binaries bundled under
 *    `assets/stub_flasher/` and uses the faster stub commands.
 *  - **ROM-only mode** ([NoStubLoader], checkbox off, or when no stub exists for
 *    the detected chip): talks only to the ROM bootloader using the commands every
 *    ESP ROM exposes - SYNC, READ_REG/WRITE_REG, FLASH_BEGIN/DATA/END (and the
 *    compressed FLASH_DEFL_* variants) and SPI_FLASH_MD5. Reading flash back works
 *    in this mode only on classic ESP32 ([EspRomLoader.readFlashRomOnly], via
 *    `READ_FLASH_SLOW`); the other ROMs have no flash-read command.
 */
interface StubLoader {
    /** True once a stub has been uploaded and confirmed running for this session. */
    val isRunning: Boolean

    /**
     * Upload and start the stub against an already-synced [loader].
     * [onLog] receives a short diagnostic line at each stage (asset read,
     * each segment upload, memFinish, the OHAI handshake) - a plain no-op
     * default so [NoStubLoader] and any future implementation aren't forced
     * to wire it up, but real implementations should call it at every point
     * that can fail, since "stub unavailable" alone doesn't say which of
     * five very different steps actually went wrong.
     */
    suspend fun start(loader: EspRomLoader, onLog: (String) -> Unit = {}): Boolean

    /**
     * Forget that a stub was ever running. Call this right after a fresh
     * ROM sync (before deciding whether to [start] one) - a hardware reset
     * wipes anything previously uploaded to RAM, so [isRunning] must not be
     * allowed to keep reporting true from a *previous* session's upload
     * once the chip has been reset and this session chooses not to
     * re-upload (e.g. the person unchecked "use stub loader" in between).
     */
    fun reset()
}

/** The default, always-available "no stub" implementation: does nothing and reports not running. */
object NoStubLoader : StubLoader {
    override val isRunning: Boolean = false
    override suspend fun start(loader: EspRomLoader, onLog: (String) -> Unit): Boolean = false
    override fun reset() {}
}

/** What a caller can rely on given the currently active [StubLoader]. */
data class StubCapabilities(val hasStub: Boolean) {
    val fastBulkErase: Boolean get() = hasStub
    val flashReadOnAllChips: Boolean get() = hasStub

    companion object {
        fun of(loader: StubLoader): StubCapabilities = StubCapabilities(hasStub = loader.isRunning)
    }
}
