package io.espflasher.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "esp_flasher_settings")

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val defaultBaudRate: Int = 115200,
    val theme: AppTheme = AppTheme.SYSTEM,
    val autoReconnect: Boolean = false,
    val consoleFontSizeSp: Int = 12,
    val saveLogsAutomatically: Boolean = false
)

/** Wraps the Settings screen's five preferences in a single observable DataStore-backed repository. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_BAUD = intPreferencesKey("default_baud_rate")
        val THEME = stringPreferencesKey("theme")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CONSOLE_FONT_SIZE = intPreferencesKey("console_font_size_sp")
        val SAVE_LOGS_AUTO = booleanPreferencesKey("save_logs_automatically")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            defaultBaudRate = prefs[Keys.DEFAULT_BAUD] ?: 115200,
            theme = prefs[Keys.THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SYSTEM,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: false,
            consoleFontSizeSp = prefs[Keys.CONSOLE_FONT_SIZE] ?: 12,
            saveLogsAutomatically = prefs[Keys.SAVE_LOGS_AUTO] ?: false
        )
    }

    suspend fun setDefaultBaudRate(baud: Int) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_BAUD] = baud }
    }

    suspend fun setTheme(theme: AppTheme) {
        context.settingsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setConsoleFontSize(sp: Int) {
        context.settingsDataStore.edit { it[Keys.CONSOLE_FONT_SIZE] = sp }
    }

    suspend fun setSaveLogsAutomatically(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SAVE_LOGS_AUTO] = enabled }
    }
}
