package com.denonmusic.app.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.bridge.BridgeQueueState
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.playlists.AddToPlaylistOutcome
import com.denonmusic.app.playlists.PlaylistRepository
import com.denonmusic.app.playlists.PlexLookupTrack
import com.denonmusic.avr.SignalType
import com.denonmusic.heos.NowPlaying
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueItem
import com.denonmusic.heos.RepeatMode
import com.denonmusic.plex.PlexPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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

/** See [PlayerViewModel.playlistPicker]. */
data class PlaylistPickerUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val playlists: List<PlexPlaylist> = emptyList(),
)

/**
 * A screen's view of "the currently selected HEOS player", shared by Browse, Now Playing and Queue:
 * what's playing, transport state, and the live queue, plus the actions those screens can take.
 *
 * The state itself, and the one resync loop and event subscription that produce it, belong to
 * [PlayerStateTracker] - a singleton, because the notification has to go on showing them after the
 * last screen is gone. This view model attaches to it for as long as the UI is up.
 *
 * Still hoisted once at [com.denonmusic.app.nav.MainScreen] level (outside the nav graph) so every
 * destination observes the same instance.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val session: HeosSession,
    private val avrSession: AvrSession,
    private val bridgeQueueController: BridgeQueueController,
    private val playbackStarter: HeosPlaybackStarter,
    private val tracker: PlayerStateTracker,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    /**
     * The receiver's state lives in [PlayerStateTracker], not here, so that the notification can go
     * on showing it with no screen attached. This view model is a screen's view of that state plus
     * the actions a screen can take.
     */
    val uiState: StateFlow<PlayerUiState> = tracker.state

    /**
     * Whether to show [com.denonmusic.app.playlists.PlaylistPickerDialog] for the currently playing
     * track, and what to fill it with - kept apart from [tracker]'s state rather than added there,
     * since this is purely this screen's own transient UI state (which dialog is open right now),
     * not anything the notification or another screen needs to agree on.
     */
    private val _playlistPicker = MutableStateFlow(PlaylistPickerUiState())
    val playlistPicker: StateFlow<PlaylistPickerUiState> = _playlistPicker.asStateFlow()

    init {
        tracker.attach(fromUi = true)
    }

    override fun onCleared() {
        tracker.detach(fromUi = true)
        super.onCleared()
    }

    fun togglePlayPause() {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val next = if (tracker.state.value.playState == PlayState.Play) PlayState.Pause else PlayState.Play
        viewModelScope.launch {
            runCatching { client.setPlayState(pid, next) }
                .onSuccess { tracker.update { it.copy(playState = next) } }
        }
    }

    fun playNext() {
        if (tracker.state.value.isBridgeModeActive) return bridgeQueueController.next()
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch { runCatching { client.playNext(pid) } }
    }

    fun playPrevious() {
        if (tracker.state.value.isBridgeModeActive) return bridgeQueueController.previous()
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch { runCatching { client.playPrevious(pid) } }
    }

    fun stop() {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.setPlayState(pid, PlayState.Stop) }
                .onSuccess { tracker.update { it.copy(playState = PlayState.Stop) } }
        }
    }

    fun toggleMute() {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val next = !tracker.state.value.muted
        viewModelScope.launch {
            runCatching { client.setMute(pid, next) }
                .onSuccess { tracker.update { it.copy(muted = next) } }
        }
    }

    /** Standby, not the HEOS module itself - the amp section is what the user means by "off" here. */
    fun powerOff() {
        val client = avrSession.avrClient ?: return
        viewModelScope.launch { runCatching { client.powerStandby() } }
    }

    fun setVolume(level: Int) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.setVolume(pid, level) }
                .onSuccess { tracker.update { it.copy(volume = level) } }
        }
    }

    /**
     * For [com.denonmusic.app.MainActivity]'s hardware volume keys: relative rather than absolute,
     * since a key press only knows "up" or "down", not a target level. Updates [tracker]
     * optimistically before the HEOS round trip, not after like [setVolume] does - holding the key
     * auto-repeats faster than a `set_volume` reply comes back, and reading the level from [tracker]
     * only once it's confirmed would have every press in that window compute from the same stale
     * value instead of stacking.
     */
    fun adjustVolume(step: Int) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val level = ((tracker.state.value.volume ?: 0) + step).coerceIn(0, 100)
        tracker.update { it.copy(volume = level) }
        viewModelScope.launch { runCatching { client.setVolume(pid, level) } }
    }

    /**
     * See [PlayerStateTracker.seekTo] - the logic lives there now, shared with
     * [com.denonmusic.app.notification.NowPlayingService] so a lock-screen scrub takes the same path
     * as this screen's own slider.
     */
    fun seekTo(positionMillis: Long) {
        viewModelScope.launch { tracker.seekTo(positionMillis) }
    }

    fun setRepeat(mode: RepeatMode) {
        if (tracker.state.value.isBridgeModeActive) return bridgeQueueController.setRepeat(mode)
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val shuffle = tracker.state.value.shuffle ?: false
        viewModelScope.launch {
            runCatching { client.setPlayMode(pid, mode, shuffle) }
                .onSuccess { tracker.update { it.copy(repeat = mode) } }
        }
    }

    fun toggleShuffle() {
        if (tracker.state.value.isBridgeModeActive) return bridgeQueueController.toggleShuffle()
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val repeat = tracker.state.value.repeat ?: RepeatMode.Off
        val next = !(tracker.state.value.shuffle ?: false)
        viewModelScope.launch {
            runCatching { client.setPlayMode(pid, repeat, next) }
                .onSuccess { tracker.update { it.copy(shuffle = next) } }
        }
    }

    fun playQueueItem(qid: Int) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch { runCatching { playbackStarter.playQueueItem(client, pid, qid) } }
    }

    fun removeFromQueue(qid: Int) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch {
            // Optimistic removal: player_queue_changed will also fire and refresh from the receiver,
            // but that round trip is visibly slower than the swipe-to-dismiss animation it follows.
            tracker.update { state -> state.copy(queue = state.queue.filterNot { it.qid == qid }) }
            runCatching { client.removeFromQueue(pid, listOf(qid)) }
                .onFailure { tracker.refreshQueue(pid) }
        }
    }

    /**
     * Moves [qid] to just before [beforeQid], or to the end when [beforeQid] is null.
     *
     * HEOS's `move_queue_item` inserts before `dqid`, and its queue ids are 1-based positions, so
     * one past the last position is what "to the end" means on the wire. Returning early on a null
     * [beforeQid] instead - which is what this used to do - made QueueScreen's "move down" button a
     * no-op on the second-to-last row, the one row whose move has no following item to land before.
     */
    fun moveQueueItem(qid: Int, beforeQid: Int?) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        val destination = beforeQid ?: (tracker.state.value.queue.size + 1)
        viewModelScope.launch {
            runCatching { client.moveQueueItem(pid, listOf(qid), destination) }
                .onFailure { e -> tracker.update { it.copy(message = e.message ?: "Move failed") } }
        }
    }

    fun clearQueue() {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.clearQueue(pid) }
                .onSuccess { tracker.update { it.copy(queue = emptyList()) } }
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val client = session.heosClient ?: return
        val pid = tracker.state.value.pid ?: return
        viewModelScope.launch {
            runCatching { client.saveQueueAsPlaylist(pid, name) }
                .onFailure { e -> tracker.update { it.copy(message = e.message ?: "Save failed") } }
                .onSuccess { tracker.update { it.copy(message = "Saved as \"$name\"") } }
        }
    }

    /**
     * The Plex-side sibling of [saveQueueAsPlaylist] - a *new* Plex playlist, not the HEOS-native
     * one that saves into HEOS's own `Playlists` source (`sid 1025`). Looks every queued track up in
     * Plex by (artist, album, title) - see [PlaylistRepository.createPlaylistFromTracks] - so a
     * track Plex doesn't have, or has more than one of with nothing to disambiguate by, is left out
     * rather than guessed at; [CreatePlaylistOutcome] reports exactly how many of the queue's tracks
     * actually made it in.
     */
    fun saveQueueAsPlexPlaylist(name: String) {
        val tracks = tracker.state.value.queue.map { PlexLookupTrack(it.song, it.artist, it.album) }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            playlistRepository.createPlaylistFromTracks(name, tracks)
                .onSuccess { outcome ->
                    val note = if (outcome.addedCount == outcome.totalCount) {
                        ""
                    } else {
                        " (${outcome.addedCount} of ${outcome.totalCount} tracks - the rest weren't found in Plex, " +
                            "or matched more than one track there)"
                    }
                    tracker.update { it.copy(message = "Created Plex playlist \"${outcome.playlist.title}\"$note") }
                }
                .onFailure { e -> tracker.update { it.copy(message = e.message ?: "Couldn't create Plex playlist") } }
        }
    }

    /**
     * Opens [com.denonmusic.app.playlists.PlaylistPickerDialog] for whatever's currently playing and
     * loads the playlist list to fill it with. Bridge-mode playback has no artist/album to match on
     * (see [BridgeQueueItem][com.denonmusic.app.bridge.BridgeQueueItem] - just a display name), so
     * this is a no-op there; [NowPlayingScreen][com.denonmusic.app.nowplaying.NowPlayingScreen]
     * doesn't show the action at all in that mode, for the same reason.
     */
    fun startAddCurrentTrackToPlaylist() {
        if (tracker.state.value.nowPlaying == null) return
        _playlistPicker.value = PlaylistPickerUiState(isOpen = true, isLoading = true)
        viewModelScope.launch {
            playlistRepository.listPlaylists()
                .onSuccess { playlists -> _playlistPicker.value = _playlistPicker.value.copy(isLoading = false, playlists = playlists) }
                .onFailure { e ->
                    _playlistPicker.value = PlaylistPickerUiState()
                    tracker.update { it.copy(message = e.message ?: "Couldn't load Plex playlists") }
                }
        }
    }

    fun cancelAddToPlaylist() {
        _playlistPicker.value = PlaylistPickerUiState()
    }

    fun confirmAddCurrentTrackToPlaylist(playlist: PlexPlaylist) {
        val np = tracker.state.value.nowPlaying
        _playlistPicker.value = PlaylistPickerUiState()
        if (np == null) return
        viewModelScope.launch {
            val track = PlexLookupTrack(np.song, np.artist, np.album)
            playlistRepository.addTrackToPlaylist(playlist, track)
                .onSuccess { outcome ->
                    val text = when (outcome) {
                        AddToPlaylistOutcome.Added -> "Added \"${np.song}\" to ${playlist.title}"
                        AddToPlaylistOutcome.Ambiguous -> "Couldn't tell which Plex track this is - more than one matched"
                        AddToPlaylistOutcome.NotFoundInPlex -> "Couldn't find \"${np.song}\" in your Plex library"
                    }
                    tracker.update { it.copy(message = text) }
                }
                .onFailure { e -> tracker.update { it.copy(message = e.message ?: "Couldn't add to playlist") } }
        }
    }

    fun dismissMessage() {
        tracker.update { it.copy(message = null) }
    }

    // -- phase-6 bridge queue pass-throughs, for QueueScreen when isBridgeModeActive -------------

    fun playBridgeQueueItem(index: Int) = bridgeQueueController.playAt(index)

    fun removeBridgeQueueItem(index: Int) = bridgeQueueController.removeAt(index)

    fun clearBridgeQueue() = bridgeQueueController.clear()
}
