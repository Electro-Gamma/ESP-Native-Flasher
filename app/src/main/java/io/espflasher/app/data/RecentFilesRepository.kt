package io.espflasher.app.data

import android.content.Context
import android.net.Uri
import io.espflasher.app.flashing.ImageSlot

data class RecentFileEntry(val uri: Uri, val fileName: String, val offset: Long)

/**
 * Small SharedPreferences-backed store for "remember previously selected
 * files" - one entry per [ImageSlot], each remembering its content:// URI,
 * display name, and the offset the person last typed in for it.
 *
 * Deliberately does NOT remember whether a slot was "enabled" (included in
 * the next flash) - that always starts unchecked for a remembered file and
 * only auto-checks when the person picks a file *this session* via Browse.
 * Otherwise a leftover file from an unrelated project quietly gets flashed
 * again just because it's still remembered, which is exactly the confusing
 * behavior this is meant to avoid.
 */
class RecentFilesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("esp_flasher_recent_files", Context.MODE_PRIVATE)

    fun save(slot: ImageSlot, uri: Uri, fileName: String, offset: Long) {
        prefs.edit()
            .putString(uriKey(slot), uri.toString())
            .putString(nameKey(slot), fileName)
            .putLong(offsetKey(slot), offset)
            .apply()
    }

    fun load(slot: ImageSlot): RecentFileEntry? {
        val uriString = prefs.getString(uriKey(slot), null) ?: return null
        val name = prefs.getString(nameKey(slot), "") ?: ""
        val offset = prefs.getLong(offsetKey(slot), slot.defaultOffset ?: 0L)
        return RecentFileEntry(Uri.parse(uriString), name, offset)
    }

    fun clear(slot: ImageSlot) {
        prefs.edit().remove(uriKey(slot)).remove(nameKey(slot)).remove(offsetKey(slot)).apply()
    }

    /** Just the offset (used to pre-fill a slot's offset field even with no file chosen yet). Null = leave blank. */
    fun loadOffsetOrNull(slot: ImageSlot): Long? {
        if (!prefs.contains(offsetKey(slot))) return slot.defaultOffset
        return prefs.getLong(offsetKey(slot), slot.defaultOffset ?: 0L)
    }

    fun saveOffset(slot: ImageSlot, offset: Long) {
        prefs.edit().putLong(offsetKey(slot), offset).apply()
    }

    private fun uriKey(slot: ImageSlot) = "${slot.name}_uri"
    private fun nameKey(slot: ImageSlot) = "${slot.name}_name"
    private fun offsetKey(slot: ImageSlot) = "${slot.name}_offset"
}
