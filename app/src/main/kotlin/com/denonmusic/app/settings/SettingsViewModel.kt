package com.denonmusic.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.media.MediaInfoRepository
import com.denonmusic.data.settings.AppSettings
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.smb.SmbCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val mediaInfoRepository: MediaInfoRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _smbTestResult = MutableStateFlow<String?>(null)
    val smbTestResult: StateFlow<String?> = _smbTestResult.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { _settings.value = it }
        }
    }

    fun setAvrHost(host: String) = viewModelScope.launch { settings.setAvrHost(host) }

    fun setAvrInputMnemonic(mnemonic: String) = viewModelScope.launch { settings.setAvrInputMnemonic(mnemonic) }

    fun setSmbCredentials(host: String, share: String, username: String, password: String) =
        viewModelScope.launch { settings.setSmbCredentials(host, share, username, password) }

    /**
     * Debug affordance for exercising the real SMB path end to end - the plan's own header parsers
     * are unit-tested against crafted fixtures, but jcifs-ng's network path has never touched a real
     * share (see docs/local-setup.md). Mirrors the Browse screen's degraded-bridge tester: manual,
     * opt-in, and the only way to verify this against real hardware until a share exists to browse.
     */
    fun testSmbPath(filePath: String) {
        val current = _settings.value
        val host = current.smbHost
        val share = current.smbShare
        if (host.isNullOrBlank() || share.isNullOrBlank()) {
            _smbTestResult.value = "Set SMB host/share first"
            return
        }
        viewModelScope.launch {
            _smbTestResult.value = "Connecting..."
            val credentials = SmbCredentials(host, share, current.smbUsername.orEmpty(), current.smbPassword.orEmpty())
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val overlay = mediaInfoRepository.overlayFor(credentials)
                    mediaInfoRepository.getFormatInfo(overlay, filePath)
                }
            }
            _smbTestResult.value = result.fold(
                onSuccess = { info -> info?.toString() ?: "Parsed nothing - unsupported extension or unreadable header" },
                onFailure = { e -> "Failed: ${e.message ?: e.toString()}" },
            )
        }
    }
}
