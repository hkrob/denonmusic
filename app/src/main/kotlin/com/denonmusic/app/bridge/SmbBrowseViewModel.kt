package com.denonmusic.app.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.media.MediaInfoRepository
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.smb.SmbCredentials
import com.denonmusic.smb.SmbEntry
import com.denonmusic.smb.SmbOverlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SmbBrowseUiState(
    /** Folder names from the share root down to the current directory; empty means the root. */
    val pathSegments: List<String> = emptyList(),
    val entries: List<SmbEntry> = emptyList(),
    val isLoading: Boolean = false,
    /** Null (not yet checked) vs. false (checked, missing) - distinguishes "loading" from "not set up". */
    val hasCredentials: Boolean? = null,
    val error: String? = null,
) {
    val currentPath: String get() = pathSegments.joinToString("/")
}

/**
 * Drives the phase-6 bridge fallback's file browser: navigating the same SMB share [SmbOverlay]
 * already reads headers and art from, so a file to bridge-play can be picked by tapping through
 * folders instead of typing an exact path by hand.
 */
@HiltViewModel
class SmbBrowseViewModel @Inject constructor(
    private val mediaInfoRepository: MediaInfoRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmbBrowseUiState())
    val uiState: StateFlow<SmbBrowseUiState> = _uiState.asStateFlow()

    private var overlay: SmbOverlay? = null
    private var lastCredentials: SmbCredentials? = null

    /**
     * Resolves credentials and loads the last-browsed folder (the share root on a first visit).
     * Safe to call repeatedly - e.g. every time the collapsible browser panel is expanded again -
     * without losing the current folder: if the same credentials are already active, this is a no-op
     * rather than a reset back to the root.
     */
    fun open() {
        viewModelScope.launch {
            val saved = settings.settings.first()
            val host = saved.smbHost?.takeIf { it.isNotBlank() }
            val share = saved.smbShare?.takeIf { it.isNotBlank() }
            if (host == null || share == null) {
                overlay = null
                lastCredentials = null
                _uiState.value = SmbBrowseUiState(hasCredentials = false)
                return@launch
            }
            val credentials = SmbCredentials(host, share, saved.smbUsername.orEmpty(), saved.smbPassword.orEmpty())
            if (credentials == lastCredentials && overlay != null) return@launch

            lastCredentials = credentials
            overlay = mediaInfoRepository.overlayFor(credentials)
            _uiState.value = SmbBrowseUiState(hasCredentials = true)
            val savedSegments = saved.smbBrowsePath?.takeIf { it.isNotBlank() }?.split("/").orEmpty()
            load(savedSegments)
        }
    }

    fun enter(entry: SmbEntry) {
        if (!entry.isDirectory) return
        load(_uiState.value.pathSegments + entry.name)
    }

    /** [index] is a position in the current breadcrumb; pass -1 for the share root. */
    fun goToBreadcrumb(index: Int) {
        val segments = _uiState.value.pathSegments
        load(if (index < 0) emptyList() else segments.take(index + 1))
    }

    fun refresh() = load(_uiState.value.pathSegments)

    /**
     * Every file under the current folder, subfolders included - what "play all"/"add all" reach for
     * instead of [SmbBrowseUiState.entries]'s single level, so a multi-disc album (`CD1/`, `CD2/`
     * subfolders, no files at the album's own level) queues in one tap. Computed on demand rather
     * than kept in [uiState]: it walks the whole subtree, which is wasted work on every navigation if
     * the user never taps play.
     */
    suspend fun filesRecursive(): List<SmbEntry> {
        val activeOverlay = overlay ?: return emptyList()
        val path = _uiState.value.currentPath
        return withContext(Dispatchers.IO) {
            runCatching { activeOverlay.listFilesRecursive(path) }.getOrDefault(emptyList())
        }
    }

    private fun load(segments: List<String>) {
        val activeOverlay = overlay ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pathSegments = segments, isLoading = true, error = null)
            val path = segments.joinToString("/")
            val entries = withContext(Dispatchers.IO) {
                runCatching { activeOverlay.listDirectory(path) }.getOrNull()
            }
            if (entries == null && segments.isNotEmpty()) {
                // A restored (or bookmarked) folder that no longer exists - land somewhere real
                // rather than an error screen, the same recovery the primary HEOS browse stack uses.
                load(emptyList())
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                entries = entries.orEmpty(),
                isLoading = false,
                error = if (entries == null) "Couldn't list this folder" else null,
            )
            settings.setSmbBrowsePath(path)
        }
    }
}
