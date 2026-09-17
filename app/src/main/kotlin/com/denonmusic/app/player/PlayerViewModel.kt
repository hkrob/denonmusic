package com.denonmusic.app.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.bridge.BridgeQueueState
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.lancontrol.LanControlManager
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.avr.SignalType
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.NowPlaying
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueItem
import com.denonmusic.heos.RepeatMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Progress(val positionMillis: Long, val durationMillis: Long)

/**
 * The technical detail HEOS itself never reports (it only ever gives song/artist/album), read
 * straight off the AVR's own telnet port instead - source-independent, so this works the same for a
 * native HEOS/DLNA queue as it did for the phase-6 bridge's [com.denonmusic.smb.AudioFormatInfo].
 */
data class TechnicalInfo(
    val signalType: SignalType?,
    val sampleRateKhz: Double?,
    val activeOutputChannels: Int,
) {
    /**
     * Sample-rate-only proxy for Hi-Res on this path: the AVR's telnet port has no command for bit
     * depth on a network-sourced signal (only [com.denonmusic.smb.AudioFormatInfo], read from a file's
     * own header, ever gets a real one) - PCM above CD quality or any DSD is treated as Hi-Res here.
     */
    val isHiRes: Boolean
        get() = signalType == SignalType.Dsd || (signalType == SignalType.Pcm && (sampleRateKhz ?: 0.0) >= 48.0)
}

/**
 * e.g. "PCM 44.1 kHz • 6 ch active" - shared by the Now Playing technical line and the AVR tab's own
 * SIGNAL section so the two never drift into showing different detail for the same live receiver
 * state.
 */
fun TechnicalInfo.summary(): String? {
    if (signalType == null && sampleRateKhz == null && activeOutputChannels == 0) return null
    return buildString {
        append(
            when (signalType) {
                SignalType.Pcm -> "PCM"
                SignalType.Dsd -> "DSD"
                SignalType.Analog -> "ANALOG"
                SignalType.Unknown, null -> "SIGNAL"
            },
        )
        sampleRateKhz?.let { append(" %.1f kHz".format(java.util.Locale.US, it)) }
        if (activeOutputChannels > 0) append(" • $activeOutputChannels ch active")
        if (isHiRes) append(" • HI-RES")
    }
}

data class PlayerUiState(
    val pid: String? = null,
    val nowPlaying: NowPlaying? = null,
    val playState: PlayState? = null,
    val volume: Int? = null,
    val muted: Boolean = false,
    val repeat: RepeatMode? = null,
    val shuffle: Boolean? = null,
    val progress: Progress = Progress(0, 0),
    val queue: List<QueueItem> = emptyList(),
    val message: String? = null,
    val bridgeQueue: BridgeQueueState = BridgeQueueState(),
    val technicalInfo: TechnicalInfo? = null,
    /**
     * True when the receiver's own local HEOS player registry answered `get_players` with an empty
     * list - a known firmware quirk where the whole-home audio system and even the official HEOS app
     * (which doesn't depend on this local call) keep working while this specific endpoint goes blank,
     * usually until the receiver is power-cycled. Surfaced explicitly rather than left to read as an
     * ordinary "nothing playing" - see [PlayerViewModel.resolvePlayerAndRefresh].
     */
    val noHeosPlayerFound: Boolean = false,
) {
    /**
     * True while [bridgeQueue] holds the track actually driving the receiver right now. Trusting our
     * own queue state as ground truth, rather than trying to infer it from HEOS's now-playing report,
     * is deliberate: the AVR-X4500H probed for this project actually reads embedded tags out of a
     * `play_stream` file and reports the *real* title/artist/album once it's parsed them - "generic
     * label means bridge mode" turned out to be false in practice, not just theoretically fragile.
     * Every action that starts real HEOS playback goes through
     * [com.denonmusic.app.heos.HeosPlaybackStarter], which clears the bridge queue and so is what
     * turns this back off.
     */
    val isBridgeModeActive: Boolean
        get() = bridgeQueue.currentItem != null
}

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
    private val bridgeQueueController: BridgeQueueController,
    private val lanControlManager: LanControlManager,
    private val playbackStarter: HeosPlaybackStarter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null
    private var resyncJob: Job? = null
    private var lastPlayState: PlayState? = null

    init {
        // Idempotent, and PlayerViewModel (created once at MainScreen level) is the natural place to
        // kick this off: it's alive for the app's whole run regardless of which tab is open, same as
        // every other session this ViewModel already bootstraps below.
        lanControlManager.ensureStarted()
        viewModelScope.launch {
            session.state.collectLatest { state ->
                if (state is HeosConnectionState.Connected) {
                    bootstrap()
                } else {
                    eventsJob?.cancel()
                    resyncJob?.cancel()
                    _uiState.value = PlayerUiState()
                }
            }
        }
        viewModelScope.launch {
            bridgeQueueController.state.collectLatest { bridgeState ->
                _uiState.value = _uiState.value.copy(bridgeQueue = bridgeState)
            }
        }
    }

    private suspend fun bootstrap() {
        // The AVR and HEOS ports live on the same box, so the same host serves both. Started here
        // too (not only from AvrViewModel) so the bit-perfect policy applies even if the user has
        // never opened the AVR tab.
        runCatching { settings.settings.first() }.getOrNull()?.avrHost?.let { avrSession.start(it) }
        startPeriodicResync()
    }

    /**
     * A poll-based safety net on top of [subscribeEvents]'s event-driven updates - independent of
     * [HeosSession]'s own now-heartbeated event socket, since even a healthy connection can miss the
     * odd event, and a third party (the receiver's own remote, another HEOS app) changing state
     * doesn't necessarily fire an event this app happens to be listening for. Never lets the UI drift
     * further than this interval from ground truth, whatever the cause.
     *
     * Also the only place [resolvePlayerAndRefresh] runs from, so re-resolving the player id is on
     * the same clock as everything else - see its own doc for why that can't be a one-shot lookup.
     */
    private fun startPeriodicResync() {
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            while (isActive) {
                resolvePlayerAndRefresh()
                delay(if (_uiState.value.noHeosPlayerFound) NO_PLAYER_RETRY_INTERVAL_MS else RESYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Re-resolves the active HEOS player on every tick via [HeosSession.resolvePid], which never
     * caches - see its doc for the receiver behaviour that makes a latched pid a trap.
     *
     * Polling here both recovers automatically once the receiver's registry comes back and surfaces
     * the gap to the user in the meantime via [PlayerUiState.noHeosPlayerFound], instead of a
     * generic-looking "nothing playing".
     */
    private suspend fun resolvePlayerAndRefresh() {
        val playerPid = session.resolvePid()
        if (playerPid == null) {
            if (!_uiState.value.noHeosPlayerFound) {
                eventsJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    noHeosPlayerFound = true,
                    pid = null,
                    nowPlaying = null,
                    playState = null,
                    queue = emptyList(),
                )
            }
            return
        }
        val recovered = _uiState.value.pid != playerPid
        _uiState.value = _uiState.value.copy(pid = playerPid, noHeosPlayerFound = false)
        if (recovered) subscribeEvents(playerPid)
        refreshAll(playerPid)
        refreshQueue(playerPid)
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
        refreshTechnicalInfo()
    }

    /**
     * Reads the signal the AVR is actually receiving right now. Unlike [applyBitPerfectPolicyOnPlaybackStart]
     * this isn't gated on a play-state edge: [refreshAll] itself only runs on a handful of HEOS
     * events (now-playing/state/volume/repeat/shuffle changed), not every progress tick, so querying
     * the AVR's telnet port here as often as that runs is cheap enough not to need its own gate.
     */
    private suspend fun refreshTechnicalInfo() {
        val client = avrSession.avrClient ?: return
        val info = TechnicalInfo(
            signalType = runCatching { client.signalType() }.getOrNull(),
            sampleRateKhz = runCatching { client.sampleRateKhz() }.getOrNull(),
            activeOutputChannels = runCatching { client.outputChannels() }.getOrDefault(emptyList()).size,
        )
        _uiState.value = _uiState.value.copy(technicalInfo = info)
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
                if (frame.eventName == "player_volume_changed") {
                    frame.attributes["mute"]?.let { mute ->
                        _uiState.value = _uiState.value.copy(muted = mute == "on")
                    }
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
        if (_uiState.value.isBridgeModeActive) return bridgeQueueController.next()
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch { runCatching { client.playNext(pid) } }
    }

    fun playPrevious() {
        if (_uiState.value.isBridgeModeActive) return bridgeQueueController.previous()
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch { runCatching { client.playPrevious(pid) } }
    }

    fun stop() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.setPlayState(pid, PlayState.Stop) }
                .onSuccess { _uiState.value = _uiState.value.copy(playState = PlayState.Stop) }
        }
    }

    fun toggleMute() {
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val next = !_uiState.value.muted
        viewModelScope.launch {
            runCatching { client.setMute(pid, next) }
                .onSuccess { _uiState.value = _uiState.value.copy(muted = next) }
        }
    }

    /** Standby, not the HEOS module itself - the amp section is what the user means by "off" here. */
    fun powerOff() {
        val client = avrSession.avrClient ?: return
        viewModelScope.launch { runCatching { client.powerStandby() } }
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
        if (_uiState.value.isBridgeModeActive) return bridgeQueueController.setRepeat(mode)
        val client = session.heosClient ?: return
        val pid = _uiState.value.pid ?: return
        val shuffle = _uiState.value.shuffle ?: false
        viewModelScope.launch {
            runCatching { client.setPlayMode(pid, mode, shuffle) }
                .onSuccess { _uiState.value = _uiState.value.copy(repeat = mode) }
        }
    }

    fun toggleShuffle() {
        if (_uiState.value.isBridgeModeActive) return bridgeQueueController.toggleShuffle()
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
        viewModelScope.launch { runCatching { playbackStarter.playQueueItem(client, pid, qid) } }
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

    // -- phase-6 bridge queue pass-throughs, for QueueScreen when isBridgeModeActive -------------

    fun playBridgeQueueItem(index: Int) = bridgeQueueController.playAt(index)

    fun removeBridgeQueueItem(index: Int) = bridgeQueueController.removeAt(index)

    fun clearBridgeQueue() = bridgeQueueController.clear()

    private companion object {
        const val RESYNC_INTERVAL_MS = 15_000L
        /** Tighter than [RESYNC_INTERVAL_MS] while no player is found, so recovery (e.g. a power cycle) shows up promptly. */
        const val NO_PLAYER_RETRY_INTERVAL_MS = 5_000L
    }
}
