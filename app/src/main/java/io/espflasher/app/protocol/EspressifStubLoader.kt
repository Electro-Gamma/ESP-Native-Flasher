package io.espflasher.app.protocol

import org.json.JSONObject
import java.io.InputStream
import java.util.Base64

/**
 * A real [StubLoader]: uploads and runs Espressif's own precompiled
 * flasher-stub firmware, then reports it as active so the rest of the app
 * can use faster, stub-only ROM commands.
 *
 * Unlike the concern [StubLoader]'s own doc comment originally raised -
 * "bundling esptool's stub blobs means embedding third-party compiled code,
 * exactly what this project avoids" - these specific binaries aren't
 * esptool. Espressif publishes them completely separately, dual-licensed
 * under Apache-2.0/MIT (see the bundled `stub_flasher/LICENSE-*` files),
 * specifically so third-party tools can embed them without depending on
 * esptool or its GPL license at all. That's a materially different
 * situation from embedding esptool.py itself, which is why this
 * implementation exists as an opt-in rather than the default: it's a
 * legitimate choice for this project to make, not a decision this file
 * makes unilaterally on the project's behalf.
 *
 * @param openAsset given a filename under `assets/stub_flasher/` (e.g.
 *   `"esp32.json"`), returns an [InputStream] for it, or null if it doesn't
 *   exist. Takes a plain function rather than an Android `Context` so this
 *   class stays testable outside Android, matching the rest of the
 *   `protocol` package - the actual `assets.open(...)` call belongs in the
 *   ViewModel/Activity layer that constructs this.
 */
class EspressifStubLoader(private val openAsset: (String) -> InputStream?) : StubLoader {

    override var isRunning: Boolean = false
        private set

    override fun reset() {
        isRunning = false
    }

    override suspend fun start(loader: EspRomLoader, onLog: (String) -> Unit): Boolean {
        isRunning = false
        val chip = loader.chip
        if (chip == null) {
            onLog("stub: no chip identified yet")
            return false
        }
        val assetName = ASSET_NAMES[chip]
        if (assetName == null) {
            onLog("stub: no published stub for ${chip.displayName} yet")
            return false
        }

        val json = try {
            openAsset("stub_flasher/$assetName")?.use { it.readBytes() }
        } catch (e: Exception) {
            onLog("stub: couldn't read $assetName asset - ${e.message}")
            return false
        }
        if (json == null) {
            onLog("stub: $assetName asset not found in the app bundle")
            return false
        }

        val obj = try {
            JSONObject(String(json, Charsets.UTF_8))
        } catch (e: Exception) {
            onLog("stub: $assetName isn't valid JSON - ${e.message}")
            return false
        }

        val decoder = Base64.getDecoder()
        val text = decoder.decode(obj.getString("text"))
        val textStart = obj.getLong("text_start")
        val entry = obj.getLong("entry")
        val data = if (obj.has("data")) decoder.decode(obj.getString("data")) else null
        val dataStart = if (obj.has("data_start")) obj.getLong("data_start") else 0L
        onLog("stub: uploading text (${text.size}B @ 0x%X)".format(textStart))

        try {
            uploadSegment(loader, text, textStart)
            if (data != null && data.isNotEmpty()) {
                onLog("stub: uploading data (${data.size}B @ 0x%X)".format(dataStart))
                uploadSegment(loader, data, dataStart)
            }
        } catch (e: Exception) {
            onLog("stub: segment upload failed - ${e.message}")
            return false
        }

        onLog("stub: handing off to entry 0x%X".format(entry))
        try {
            loader.memFinish(entry)
        } catch (e: Exception) {
            // memFinish deliberately doesn't wait for a normal response (see its
            // own doc comment - the jump itself can disrupt the in-flight reply),
            // so a failure here is unusual, but not impossible.
            onLog("stub: memFinish failed - ${e.message}")
            return false
        }

        // The stub's first act on starting is to send this 4-byte greeting
        // completely unprompted - see readRawFrame's doc comment for why
        // this can't go through the normal command()/response path.
        val greeting = loader.readRawFrame(timeoutMs = 3000)
        if (greeting == null) {
            onLog("stub: no greeting received within 3s after handoff")
            return false
        }
        val ok = greeting.contentEquals(OHAI)
        if (!ok) {
            onLog("stub: greeting was ${greeting.joinToString(" ") { "%02X".format(it) }}, expected 4F 48 41 49 (\"OHAI\")")
        }
        isRunning = ok
        return ok
    }

    private suspend fun uploadSegment(loader: EspRomLoader, segment: ByteArray, offset: Long) {
        val blockSize = EspCommand.RAM_BLOCK_SIZE
        val blocks = (segment.size + blockSize - 1) / blockSize
        loader.memBegin(segment.size.toLong(), blocks, blockSize, offset)
        var seq = 0
        var pos = 0
        while (pos < segment.size) {
            val len = minOf(blockSize, segment.size - pos)
            loader.memBlock(segment.copyOfRange(pos, pos + len), seq)
            pos += len
            seq++
        }
    }

    companion object {
        private val OHAI = byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte())

        /**
         * Chip -> filename under `assets/stub_flasher/` (e.g. `esp32.json`). Espressif's stub
         * releases (esp-flasher-stub v1.0.0) don't cover every chip this app
         * knows about yet - [ChipType.ESP32_H21] and [ChipType.ESP32_E22]
         * have no published stub build, so [start] cleanly reports false for
         * those rather than failing partway through an upload. ESP32-P4 also
         * ships a separate `esp32p4-rev1.json` for an older silicon revision
         * that isn't distinguished here; this uses the current-revision build.
         */
        private val ASSET_NAMES: Map<ChipType, String> = mapOf(
            ChipType.ESP8266 to "esp8266.json",
            ChipType.ESP32 to "esp32.json",
            ChipType.ESP32_S2 to "esp32s2.json",
            ChipType.ESP32_S3 to "esp32s3.json",
            ChipType.ESP32_C2 to "esp32c2.json",
            ChipType.ESP32_C3 to "esp32c3.json",
            ChipType.ESP32_C5 to "esp32c5.json",
            ChipType.ESP32_C6 to "esp32c6.json",
            ChipType.ESP32_C61 to "esp32c61.json",
            ChipType.ESP32_H2 to "esp32h2.json",
            ChipType.ESP32_H4 to "esp32h4.json",
            ChipType.ESP32_P4 to "esp32p4.json",
            ChipType.ESP32_S31 to "esp32s31.json"
            // ESP32_H21, ESP32_E22: no stub published - intentionally absent, see doc comment above.
        )
    }
}
