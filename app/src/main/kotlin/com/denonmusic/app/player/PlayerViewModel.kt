package com.denonmusic.app.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.NowPlaying
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueItem
import com.denonmusic.heos.RepeatMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class Progress(val positionMillis: Long, val durationMillis: Long)

data class PlayerUiState(
    val pid: String? = null,
    val nowPlaying: NowPlaying? = null,
    val playState: PlayState? = null,
    val volume: Int? = null,
    val repeat: RepeatMode? = null,
    val shuffle: Boolean? = null,
    val progress: Progress = Progress(0, 0),
    val queue: List<QueueItem> = emptyList(),
    val message: String? = null,
)

/**
 * Owns everything about "the currently selected HEOS player" that Browse, Now Playing and Queue all
 * need: which player, what's playing, transport state, and the live queue. Hoisted once at
 * [com.denonmusic.app.nav.MainScreen] level (outside the nav graph) so every destination observes the
 * same instance instead of each re-resolving the player id and re-subscribing to the event socket.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val session: HeosSession,
    private val avrSession: AvrSession,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null
    private var lastPlayState: PlayState? = null

    init {
        viewModelScope.launch {
            session.state.collectLatest { state ->
                if (state is HeosConnectionState.Connected) {
                    bootstrap()
                } else {
                    eventsJob?.cancel()
                    _uiState.value = PlayerUiState()
                }
            }
        }
    }

    private suspend fun bootstrap() {
        val client = session.heosClient ?: return
        val player = runCatching { client.getPlayers() }.getOrNull()?.firstOrNull() ?: return
        _uiState.value = _uiState.value.copy(pid = player.pid)
        // The AVR and HEOS ports live on the same box, so the same host serves both. Started here
        // too (not only from AvrViewModel) so the bit-perfect policy applies even if the user has
        // never opened the AVR tab.
        runCatching { settings.settings.first() }.getOrNull()?.avrHost?.let { avrSession.start(it) }
        refreshAll(player.pid)
        refreshQueue(player.pid)
        subscribeEvents(player.pid)
    }

    private suspend fun refreshAll(pid: String) {
        val client = session.heosClient ?: return
        val nowPlaying = runCatching { client.getNowPlaying(pid) }.getOrNull()
        val playState = runCatching { client.getPlayState(pid) }.getOrNull()
        val volume = runCatching { client.getVolume(pid) }.getOrNull()
        val playMode = runCatching { client.getPlayMode(pid) }.getOrNull()
        _uiState.value = _uiState.value.copy(
            nowPlaying = nowPlaying,
            playState = playState,
            volume = volume,
            repeat = playMode?.repeat,
            shuffle = playMode?.shuffle,
        )
        if (playState == PlayState.Play && lastPlayState != PlayState.Play) {
            applyBitPerfectPolicyOnPlaybackStart()
        }
        lastPlayState = playState
    }

    /**
     * The plan's bit-perfect policy applies "on queue-start": the moment playback transitions into
     * [PlayState.Play], not on every progress tick. `lastPlayState` in [refreshAll] is what turns a
     * level (current state) into that edge (state that just changed).
     */
    private suspend fun applyBitPerfectPolicyOnPlaybackStart() {
        val client = avrSession.avrClient ?: return
        val policyName = runCatching { settings.settings.first() }.getOrNull()?.bitPerfectPolicy ?: return
        val policy = runCatching { BitPerfectPolicy.valueOf(policyName) }.getOrNull() ?: return
        runCatching { client.applyBitPerfectPolicy(policy) }
    }

    private suspend fun refreshQueue(pid: String) {
        val client = session.heosClient ?: return
        val items = runCatching { client.getQueue(pid) }.getOrNull().orEmpty()
        _uiState.value = _uiState.value.copy(queue = items)
    }

    private fun subscribeEvents(pid: String) {
        eventsJob?.cancel()
        val events = session.events ?: return
        eventsJob = viewModelScope.launch {
            events.collect { frame ->
                when (frame.eventName) {
                    "player_now_playing_changed", "player_state_changed", "player_volume_changed",
                    "repeat_mode_changed", "shuffle_mode_changed",
                    -> refreshAll(pid)
                    "player_now_playing_progress" -> {
                        val position = frame.attributes["cur_pos"]?.toLongOrNull() ?: 0L
                        val duration = frame.attributes["duration"]?.toLongOrNull() ?: 0L
                        _uiState.value = _uiState.value.copy(progress = Progress(position, duration))
                    }
                    "player_queue_changed" -> refreshQueue(pid)
                }
            }
        }
    }

    fun togglePlayPause() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val next = if (_uiState.value.playState == PlayState.Play) PlayState.Pause else PlayState.Play
        viewModelScope.launch {
            runCatching { client.setPlayState(pid, next) }
                .onSuccess { _uiState.value = _uiState.value.copy(playState = next) }
        }
    }

    fun playNext() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch { runCatching { client.playNext(pid) } }
    }

    fun playPrevious() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch { runCatching { client.playPrevious(pid) } }
    }

    fun setVolume(level: Int) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.setVolume(pid, level) }
                .onSuccess { _uiState.value = _uiState.value.copy(volume = level) }
        }
    }

    fun setRepeat(mode: RepeatMode) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val shuffle = _uiState.value.shuffle ?: false
        viewModelScope.launch {
            runCatching { client.setPlayMode(pid, mode, shuffle) }
                .onSuccess { _uiState.value = _uiState.value.copy(repeat = mode) }
        }
    }

    fun toggleShuffle() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val repeat = _uiState.value.repeat ?: RepeatMode.Off
        val next = !(_uiState.value.shuffle ?: false)
        viewModelScope.launch {
            runCatching { client.setPlayMode(pid, repeat, next) }
                .onSuccess { _uiState.value = _uiState.value.copy(shuffle = next) }
        }
    }

    fun playQueueItem(qid: Int) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch { runCatching { client.playQueueItem(pid, qid) } }
    }

    fun removeFromQueue(qid: Int) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch {
            // Optimistic removal: player_queue_changed will also fire and refresh from the receiver,
            // but that round trip is visibly slower than the swipe-to-dismiss animation it follows.
            _uiState.value = _uiState.value.copy(queue = _uiState.value.queue.filterNot { it.qid == qid })
            runCatching { client.removeFromQueue(pid, listOf(qid)) }
                .onFailure { refreshQueue(pid) }
        }
    }

    /** Moves [qid] to just before [beforeQid], or to the end when [beforeQid] is null. */
    fun moveQueueItem(qid: Int, beforeQid: Int?) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val destination = beforeQid ?: return
        viewModelScope.launch {
            runCatching { client.moveQueueItem(pid, listOf(qid), destination) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "Move failed") }
        }
    }

    fun clearQueue() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.clearQueue(pid) }
                .onSuccess { _uiState.value = _uiState.value.copy(queue = emptyList()) }
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.saveQueueAsPlaylist(pid, name) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "Save failed") }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Saved as \"$name\"") }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
