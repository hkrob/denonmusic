package com.denonmusic.app.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.data.browse.BrowseStackEntity
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.NowPlaying
import com.denonmusic.heos.PlayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class BrowseUiState(
    val connection: HeosConnectionState = HeosConnectionState.Disconnected,
    val breadcrumb: List<BrowseStackEntity> = emptyList(),
    val items: List<BrowseItem> = emptyList(),
    val isPlayableContainer: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val avrHost: String? = null,
    val nowPlaying: NowPlaying? = null,
    val playState: PlayState? = null,
    val volume: Int? = null,
)

/** One long-press menu offering, mapping 1:1 onto the wire `aid` values. */
enum class QueueAction(val criteria: AddCriteria, val label: String) {
    PlayNow(AddCriteria.PlayNow, "Play now"),
    PlayNext(AddCriteria.PlayNext, "Play next"),
    AddToEnd(AddCriteria.AddToEnd, "Add to end"),
    ReplaceAndPlay(AddCriteria.ReplaceAndPlay, "Replace queue and play"),
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val session: HeosSession,
    private val browseRepository: BrowseRepository,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var pid: String? = null
    private var listingJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settings.settings.first()
            _uiState.value = _uiState.value.copy(avrHost = saved.avrHost)
            saved.avrHost?.let { session.start(it) }
        }
        viewModelScope.launch {
            session.state.collectLatest { state ->
                _uiState.value = _uiState.value.copy(connection = state)
                if (state is HeosConnectionState.Connected) bootstrap()
            }
        }
        viewModelScope.launch {
            browseRepository.observeStack().collectLatest { stack ->
                _uiState.value = _uiState.value.copy(breadcrumb = stack)
            }
        }
    }

    fun setAvrHost(host: String) {
        viewModelScope.launch {
            settings.setAvrHost(host)
            _uiState.value = _uiState.value.copy(avrHost = host)
            session.start(host)
        }
    }

    private var eventsJob: Job? = null

    private suspend fun bootstrap() {
        val client = session.heosClient ?: return
        runCatching { client.getPlayers() }.getOrNull()?.firstOrNull()?.let { pid = it.pid }
        pid?.let { refreshPlayerState(it) }
        subscribeToPlayerEvents()

        val existingStack = browseRepository.currentStack()
        if (existingStack.isEmpty()) {
            val source = sourceRepository.detectAndPersist(client)
            if (source == null) {
                _uiState.value = _uiState.value.copy(message = "No queueable music source found. Add a share in the HEOS app.")
                return
            }
            browseRepository.replaceStack(listOf(BrowseLevel(sid = source.sid, cid = null, displayName = source.name)))
        } else {
            val resolved = browseRepository.restoreResolving(client)
            if (resolved.isEmpty()) {
                val source = sourceRepository.detectAndPersist(client)
                if (source != null) {
                    browseRepository.replaceStack(listOf(BrowseLevel(sid = source.sid, cid = null, displayName = source.name)))
                }
            }
        }
        refreshCurrentLevel()
    }

    /**
     * Reads the target level straight from [BrowseRepository] rather than `_uiState.breadcrumb`:
     * that field is populated by a separate collector on [BrowseRepository.observeStack] and is not
     * guaranteed to have caught up with a `replaceStack` this same call chain just made.
     */
    private suspend fun refreshCurrentLevel() {
        val client = session.heosClient ?: return
        val target = browseRepository.currentStack().lastOrNull() ?: return
        listingJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        listingJob = viewModelScope.launch {
            browseRepository.listing(client, target.sid, target.cid).collectLatest { listing ->
                _uiState.value = _uiState.value.copy(
                    items = listing.items,
                    isPlayableContainer = listing.isPlayableContainer,
                    isLoading = false,
                )
            }
        }
    }

    fun open(item: BrowseItem) {
        if (session.heosClient == null) return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        if (!item.isContainer || item.cid == null) return
        viewModelScope.launch {
            val newStack = _uiState.value.breadcrumb + BrowseStackEntity(
                position = _uiState.value.breadcrumb.size,
                sid = current.sid,
                cid = item.cid,
                displayName = item.name,
            )
            browseRepository.replaceStack(
                newStack.map { BrowseLevel(it.sid, it.cid, it.displayName) },
            )
            refreshCurrentLevel()
        }
    }

    fun goToBreadcrumb(position: Int) {
        viewModelScope.launch {
            browseRepository.truncateAfter(position)
            refreshCurrentLevel()
        }
    }

    fun onScroll(position: Int, index: Int, offset: Int) {
        viewModelScope.launch { browseRepository.updateScroll(position, index, offset) }
    }

    fun queue(item: BrowseItem, action: QueueAction) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        viewModelScope.launch {
            runCatching {
                client.addToQueue(
                    pid = playerId,
                    sid = current.sid,
                    cid = item.cid ?: current.cid.orEmpty(),
                    mid = item.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${item.name}")
            }
        }
    }

    fun playAllCurrentContainer(action: QueueAction) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        val cid = current.cid ?: return
        viewModelScope.launch {
            runCatching {
                client.addToQueue(pid = playerId, sid = current.sid, cid = cid, criteria = action.criteria)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${current.displayName}")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun refreshPlayerState(playerId: String) {
        val client = session.heosClient ?: return
        val nowPlaying = runCatching { client.getNowPlaying(playerId) }.getOrNull()
        val playState = runCatching { client.getPlayState(playerId) }.getOrNull()
        val volume = runCatching { client.getVolume(playerId) }.getOrNull()
        _uiState.value = _uiState.value.copy(nowPlaying = nowPlaying, playState = playState, volume = volume)
    }

    /** Drives the now-playing bar from the event socket instead of polling. */
    private fun subscribeToPlayerEvents() {
        eventsJob?.cancel()
        val events = session.events ?: return
        eventsJob = viewModelScope.launch {
            events.collect { frame ->
                val playerId = pid ?: return@collect
                when (frame.eventName) {
                    "player_now_playing_changed", "player_state_changed", "player_volume_changed" ->
                        refreshPlayerState(playerId)
                }
            }
        }
    }

    fun togglePlayPause() {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        val next = if (_uiState.value.playState == PlayState.Play) PlayState.Pause else PlayState.Play
        viewModelScope.launch {
            runCatching { client.setPlayState(playerId, next) }
                .onSuccess { _uiState.value = _uiState.value.copy(playState = next) }
        }
    }

    /**
     * Bridge fallback ([AddCriteria] doesn't apply - this is `browse/play_stream`, not a queue
     * entry): starts exactly one stream, no next-track, no real queue. Kept out of the primary
     * browse flow deliberately per the plan - this exists only for exercising playback on the
     * receiver when no HEOS-indexed source is available yet.
     */
    fun playBridgeUrl(url: String) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        viewModelScope.launch {
            runCatching { client.playStream(playerId, url) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(message = e.message ?: "Stream failed") }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Streaming (degraded bridge mode)") }
        }
    }

    fun setVolume(level: Int) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        viewModelScope.launch {
            runCatching { client.setVolume(playerId, level) }
                .onSuccess { _uiState.value = _uiState.value.copy(volume = level) }
        }
    }
}
