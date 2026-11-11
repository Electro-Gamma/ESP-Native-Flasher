package io.espflasher.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.espflasher.app.data.AppSettings
import io.espflasher.app.data.AppTheme
import io.espflasher.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { _settings.value = it }
        }
    }

    fun setDefaultBaudRate(baud: Int) = viewModelScope.launch { repository.setDefaultBaudRate(baud) }
    fun setTheme(theme: AppTheme) = viewModelScope.launch { repository.setTheme(theme) }
    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch { repository.setAutoReconnect(enabled) }
    fun setConsoleFontSize(sp: Int) = viewModelScope.launch { repository.setConsoleFontSize(sp) }
    fun setSaveLogsAutomatically(enabled: Boolean) = viewModelScope.launch { repository.setSaveLogsAutomatically(enabled) }
}
