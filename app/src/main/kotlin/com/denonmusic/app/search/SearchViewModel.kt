package com.denonmusic.app.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.browse.BrowseRepository
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.playlists.AddToPlaylistOutcome
import com.denonmusic.app.playlists.PlaylistRepository
import com.denonmusic.app.playlists.PlexLookupTrack
import com.denonmusic.plex.PlexPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val indexStatus: SearchIndexStatus = SearchIndexStatus.NeverIndexed,
    val message: String? = null,
    /** Non-null shows [com.denonmusic.app.playlists.PlaylistPickerDialog] for this result. */
    val addToPlaylistTarget: SearchResult? = null,
    val isLoadingPlaylists: Boolean = false,
    val plexPlaylists: List<PlexPlaylist> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: HeosSession,
    private val indexRepository: SearchIndexRepository,
    private val browseRepository: BrowseRepository,
    private val playbackStarter: HeosPlaybackStarter,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            indexRepository.status.collectLatest { status ->
                _uiState.value = _uiState.value.copy(indexStatus = status)
            }
        }
    }

    /**
     * Starts [SearchIndexService] rather than calling [SearchIndexRepository.runReindex] directly -
     * see that service's own doc for why a crawl over a large library needs to be a foreground
     * service, not a screen-scoped call.
     *
     * `startForegroundService` can itself throw - a `ForegroundServiceStartNotAllowedException` on a
     * restricted background start, same risk `NowPlayingShade.setShowing` already guards against -
     * so this reports it as an ordinary [SearchIndexStatus.Failed] rather than crashing the screen
     * the user just tapped a button on.
     */
    fun reindex() {
        runCatching { SearchIndexService.start(context) }
            .onFailure { e -> _uiState.value = _uiState.value.copy(message = "Couldn't start indexing: ${e.message}") }
    }

    /**
     * Runs on every keystroke rather than only once the user stops typing: a query against the
     * already-built local index is one SQLite `LIKE`, not a network round trip, so there is nothing
     * here for debouncing to save. [collectLatest]-style cancellation (via replacing [searchJob]) is
     * kept anyway so a very fast typist never has an earlier query's results land after a later one's.
     */
    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            val results = indexRepository.search(query)
            _uiState.value = _uiState.value.copy(results = results, isSearching = false)
        }
    }

    /** Pushes the result's full ancestor path onto the shared browse stack; the caller switches tabs. */
    fun openContainer(result: SearchResult) {
        if (!result.isContainer) return
        viewModelScope.launch { browseRepository.replaceStack(result.path) }
    }

    fun queue(result: SearchResult, action: QueueAction) {
        if (!result.isTrack && !result.isContainer) return
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
                    sid = result.queueSid,
                    cid = result.queueCid,
                    mid = result.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${result.name}")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** Opens [PlaylistPickerDialog][com.denonmusic.app.playlists.PlaylistPickerDialog] for [result]
     * and loads the playlist list to fill it with - only [result]s that are tracks have anything to
     * add, same restriction [queue] already applies for the same reason. */
    fun startAddToPlaylist(result: SearchResult) {
        if (!result.isTrack) return
        _uiState.value = _uiState.value.copy(addToPlaylistTarget = result, isLoadingPlaylists = true)
        viewModelScope.launch {
            playlistRepository.listPlaylists()
                .onSuccess { playlists -> _uiState.value = _uiState.value.copy(isLoadingPlaylists = false, plexPlaylists = playlists) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        addToPlaylistTarget = null,
                        isLoadingPlaylists = false,
                        message = e.message ?: "Couldn't load Plex playlists",
                    )
                }
        }
    }

    fun cancelAddToPlaylist() {
        _uiState.value = _uiState.value.copy(addToPlaylistTarget = null)
    }

    fun confirmAddToPlaylist(playlist: PlexPlaylist) {
        val target = _uiState.value.addToPlaylistTarget ?: return
        _uiState.value = _uiState.value.copy(addToPlaylistTarget = null)
        viewModelScope.launch {
            val track = PlexLookupTrack(target.name, target.artist, target.album)
            playlistRepository.addTrackToPlaylist(playlist, track)
                .onSuccess { outcome ->
                    val text = when (outcome) {
                        AddToPlaylistOutcome.Added -> "Added \"${target.name}\" to ${playlist.title}"
                        AddToPlaylistOutcome.Ambiguous -> "Couldn't tell which Plex track this is - more than one matched"
                        AddToPlaylistOutcome.NotFoundInPlex -> "Couldn't find \"${target.name}\" in your Plex library"
                    }
                    _uiState.value = _uiState.value.copy(message = text)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(message = e.message ?: "Couldn't add to playlist") }
        }
    }
}
