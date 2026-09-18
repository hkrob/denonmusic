package com.denonmusic.app.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val progress: Int, val sizeBytes: Long = 0L) : UpdateUiState
    data class ReadyToInstall(val file: File, val versionName: String, val sizeBytes: Long = 0L) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val store = UpdatePrefsStore(context)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    // Populated at startup from whatever the background check last found, so the About tab can show
    // "an update is available" immediately without a network call on open.
    private val _cachedRelease = MutableStateFlow<ReleaseInfo?>(null)
    val cachedRelease: StateFlow<ReleaseInfo?> = _cachedRelease.asStateFlow()

    init {
        val cached = store.loadLatestRelease()
        if (cached != null && UpdateManager.isNewer(cached.versionName, BuildConfig.VERSION_NAME)) {
            _cachedRelease.value = cached
        } else if (cached != null) {
            store.clearLatestRelease()
        }
    }

    fun check() {
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            try {
                val latest = UpdateManager.checkLatest()
                when {
                    latest == null -> _state.value = UpdateUiState.Error("Couldn't reach GitHub, or the latest release has no APK")
                    UpdateManager.isNewer(latest.versionName, BuildConfig.VERSION_NAME) -> {
                        store.saveLatestRelease(latest)
                        _cachedRelease.value = latest
                        _state.value = UpdateUiState.Available(latest)
                    }
                    else -> {
                        store.clearLatestRelease()
                        _cachedRelease.value = null
                        _state.value = UpdateUiState.UpToDate
                    }
                }
            } catch (e: Exception) {
                _state.value = UpdateUiState.Error(e.message ?: "Update check failed")
            }
        }
    }

    fun download(release: ReleaseInfo) {
        _state.value = UpdateUiState.Downloading(0, release.apkSizeBytes)
        viewModelScope.launch {
            try {
                val file = UpdateManager.download(context, release) { progress ->
                    _state.value = UpdateUiState.Downloading(progress, release.apkSizeBytes)
                }
                _state.value = UpdateUiState.ReadyToInstall(file, release.versionName, release.apkSizeBytes)
            } catch (e: Exception) {
                _state.value = UpdateUiState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun reset() {
        _state.value = UpdateUiState.Idle
    }
}
