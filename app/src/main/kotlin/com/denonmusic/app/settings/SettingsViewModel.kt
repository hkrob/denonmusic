package com.denonmusic.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.data.settings.AppSettings
import com.denonmusic.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { _settings.value = it }
        }
    }

    fun setAvrHost(host: String) = viewModelScope.launch { settings.setAvrHost(host) }

    fun setAvrInputMnemonic(mnemonic: String) = viewModelScope.launch { settings.setAvrInputMnemonic(mnemonic) }

    fun setSmbCredentials(host: String, share: String, username: String, password: String) =
        viewModelScope.launch { settings.setSmbCredentials(host, share, username, password) }
}
