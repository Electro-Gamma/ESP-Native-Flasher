package io.espflasher.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.espflasher.app.data.AppTheme
import io.espflasher.app.databinding.ActivitySettingsBinding
import io.espflasher.app.usb.SerialConfig
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    private val themeLabels = listOf("System default", "Light", "Dark")
    private val themeValues = listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.defaultBaudDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, SerialConfig.COMMON_BAUD_RATES.map { it.toString() })
        )
        binding.themeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, themeLabels)
        )

        binding.defaultBaudDropdown.setOnItemClickListener { _, _, position, _ ->
            val baud = SerialConfig.COMMON_BAUD_RATES.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setDefaultBaudRate(baud)
        }
        binding.themeDropdown.setOnItemClickListener { _, _, position, _ ->
            val theme = themeValues.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setTheme(theme)
            applyTheme(theme)
        }
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setAutoReconnect(checked) }
        binding.saveLogsAutoSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setSaveLogsAutomatically(checked) }
        binding.consoleFontSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setConsoleFontSize(value.toInt())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    binding.defaultBaudDropdown.setText(settings.defaultBaudRate.toString(), false)
                    val themeIndex = themeValues.indexOf(settings.theme).coerceAtLeast(0)
                    binding.themeDropdown.setText(themeLabels[themeIndex], false)
                    binding.autoReconnectSwitch.isChecked = settings.autoReconnect
                    binding.saveLogsAutoSwitch.isChecked = settings.saveLogsAutomatically
                    if (binding.consoleFontSizeSlider.value != settings.consoleFontSizeSp.toFloat()) {
                        binding.consoleFontSizeSlider.value = settings.consoleFontSizeSp.toFloat().coerceIn(8f, 20f)
                    }
                }
            }
        }
    }

    private fun applyTheme(theme: AppTheme) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}
