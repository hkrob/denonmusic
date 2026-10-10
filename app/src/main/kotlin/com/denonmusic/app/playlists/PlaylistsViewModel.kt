package com.denonmusic.app.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.heos.QueueTarget
import com.denonmusic.plex.PlexPlaylist
import com.denonmusic.plex.PlexPlaylistItem
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

    // No init { refresh() } here - PlaylistsScreen's own LaunchedEffect(Unit) calls refreshOnReentry()
    // on first composition too, so one more call here would just be a redundant first fetch. See
    // refreshOnReentry's own doc for why a call on every re-entry, not just the first, is the actual
    // fix this needs.

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
     * Call this - not [refresh] or [refreshSelectedIfOpen] alone - every time [PlaylistsScreen]
     * re-enters composition (switching tabs and back), since this ViewModel's store survives that
     * switch, same as Browse's and Queue's: without this, [uiState] goes on showing whatever it
     * showed the moment this ViewModel was first created, no matter what changed meanwhile.
     *
     * Two real reports, same root cause, caught live on a real phone (see docs/local-setup.md,
     * 2026-10-10 and 2026-10-11): (1) opening a playlist against an empty Search index, reindexing,
     * switching tabs away and back, showed the same "nothing matched" result from before the
     * reindex; (2) saving Plex server/token in Settings for the *first* time, then opening this tab,
     * still showed "no playlists - set your Plex details", because [refresh] had already run once
     * against the not-yet-configured state back when this ViewModel was created and nothing told it
     * to try again - a full app restart was the only thing that created a fresh ViewModel instance
     * to pick the new settings up. [refresh] re-fetches the playlist list itself (cheap: it is what
     * picks up Plex being configured, or re-configured, after the first time); [refreshSelectedIfOpen]
     * re-resolves whichever playlist is open, if any.
     */
    fun refreshOnReentry() {
        refresh()
        refreshSelectedIfOpen()
    }

    private fun refreshSelectedIfOpen() {
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

    /**
     * [target] is whichever one the caller already resolved to a single queueable track - either
     * [ResolvedTrack.Matched]'s own target, or one candidate the user picked off
     * [ResolvedTrack.Ambiguous] - this doesn't need to know which.
     */
    fun queueTrack(item: PlexPlaylistItem, target: QueueTarget, action: QueueAction) {
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
                    sid = target.sid,
                    cid = target.cid,
                    mid = target.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${item.title}")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
