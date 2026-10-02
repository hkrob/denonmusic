package com.denonmusic.app.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.browse.BrowseRepository
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val session: HeosSession,
    private val indexRepository: SearchIndexRepository,
    private val browseRepository: BrowseRepository,
    private val playbackStarter: HeosPlaybackStarter,
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

    fun reindex() = indexRepository.startReindex()

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
}
