package com.denonmusic.app.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.plex.PlexPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlexPlaylist> = emptyList(),
    val selected: ResolvedPlaylist? = null,
    val message: String? = null,
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val session: HeosSession,
    private val playbackStarter: HeosPlaybackStarter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.listPlaylists()
                .onSuccess { playlists -> _uiState.value = _uiState.value.copy(isLoading = false, playlists = playlists) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, message = e.message ?: "Couldn't load playlists")
                }
        }
    }

    /**
     * Re-resolves whatever playlist is currently open, if any - call this when the screen re-enters
     * composition (switching tabs and back), since this ViewModel's store survives that switch, same
     * as Browse's and Queue's, so [uiState] still shows a resolution made before the Search index
     * last changed underneath it. Caught live on a real phone (see docs/local-setup.md, 2026-10-10):
     * opening a playlist against an empty index, reindexing, switching tabs away and back, showed the
     * same "nothing matched" result from before the reindex, because nothing had told this screen to
     * look again. A no-op when nothing is open (the list view has nothing stale to refresh).
     */
    fun refreshSelectedIfOpen() {
        val playlist = _uiState.value.selected?.playlist ?: return
        open(playlist)
    }

    fun open(playlist: PlexPlaylist) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.resolve(playlist)
                .onSuccess { resolved -> _uiState.value = _uiState.value.copy(isLoading = false, selected = resolved) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isLoading = false, message = e.message ?: "Couldn't load playlist") }
        }
    }

    fun closePlaylist() {
        _uiState.value = _uiState.value.copy(selected = null)
    }

    /**
     * Queues every matched track in playlist order - mirrors `BrowseViewModel.playAllCurrentContainer`
     * exactly, since this is the same operation ("queue a resolved list of targets") sourced from
     * Plex instead of a HEOS browse walk, including the same multi-part "replace and play" handling
     * `HeosPlaybackStarter.addAll` already owns.
     */
    fun playAll(action: QueueAction) {
        val resolved = _uiState.value.selected ?: return
        val targets = resolved.matchedTargets
        if (targets.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "Nothing in this playlist matched the search index")
            return
        }
        val client = session.heosClient ?: return
        viewModelScope.launch {
            val playerId = session.resolvePid() ?: run {
                _uiState.value = _uiState.value.copy(message = "No HEOS player found")
                return@launch
            }
            runCatching { playbackStarter.addAll(client, playerId, targets, action.criteria) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed") }
                .onSuccess {
                    val skipped = resolved.tracks.size - targets.size
                    val note = if (skipped > 0) " ($skipped of ${resolved.tracks.size} not matched)" else ""
                    _uiState.value = _uiState.value.copy(message = "${action.label}: ${resolved.playlist.title}$note")
                }
        }
    }

    fun queueTrack(track: ResolvedTrack.Matched, action: QueueAction) {
        val client = session.heosClient ?: return
        viewModelScope.launch {
            val playerId = session.resolvePid() ?: run {
                _uiState.value = _uiState.value.copy(message = "No HEOS player found")
                return@launch
            }
            runCatching {
                playbackStarter.addToQueue(
                    client = client,
                    pid = playerId,
                    sid = track.target.sid,
                    cid = track.target.cid,
                    mid = track.target.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${track.item.title}")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
