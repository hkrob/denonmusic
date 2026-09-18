package com.denonmusic.app.bridge

import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.heos.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phase-6 bridge's own queue: `play_stream` starts exactly one stream with no real queue behind
 * it, so "play a folder" and "next track" have to be reimplemented client-side. [BridgeQueueLogic]
 * holds the pure sequencing rules; this class is the thin layer of side effects around it - starting
 * a stream via [SmbBridgeService] and [HeosSession], and listening for the receiver's own
 * `player_state_changed` -> `stop` event to advance automatically when a track finishes.
 *
 * A HEOS "stop" can also mean "nothing was ever queued here" or "the user paused the primary HEOS
 * queue" - [advance] guards against treating either as this queue's own track ending by checking
 * there's actually a current bridge item first.
 */
@Singleton
class BridgeQueueController @Inject constructor(
    private val heosSession: HeosSession,
    private val smbBridgeService: SmbBridgeService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BridgeQueueState())
    val state: StateFlow<BridgeQueueState> = _state.asStateFlow()

    private var eventsJob: Job? = null
    private var playJob: Job? = null

    /**
     * Set right after issuing `play_stream` for the current track. Starting a *new* stream while an
     * old one is still technically "playing" makes the receiver emit its own transitional
     * `player_state_changed` -> `stop` for the outgoing stream a moment later - confirmed against the
     * real AVR-X4500H, where that stray `stop` reached [subscribeEvents] and was misread as "the
     * track that was just started already finished", skipping straight to track 2 the instant track 1
     * began. A track legitimately playing to completion takes far longer than this window, so `stop`
     * events inside it are the receiver's own transition noise, not a real end-of-track.
     */
    private var currentTrackStartedAtMs: Long = 0L

    init {
        scope.launch {
            heosSession.state.collectLatest { connectionState ->
                if (connectionState is HeosConnectionState.Connected) subscribeEvents()
            }
        }
    }

    fun replaceQueueAndPlay(items: List<BridgeQueueItem>, startIndex: Int = 0) {
        _state.value = BridgeQueueLogic.replaceQueue(_state.value, items, startIndex)
        playCurrent()
    }

    /** Appends without disturbing playback; starts playing if the queue was empty. */
    fun addToQueue(items: List<BridgeQueueItem>) {
        val before = _state.value
        _state.value = BridgeQueueLogic.addToEnd(before, items)
        if (before.currentIndex == -1 && _state.value.currentIndex == 0) playCurrent()
    }

    fun playAt(index: Int) {
        if (index !in _state.value.items.indices) return
        _state.value = _state.value.copy(currentIndex = index, currentFormatInfo = null, currentArtwork = null)
        playCurrent()
    }

    fun next() = stepAndPlay { BridgeQueueLogic.next(it) }

    fun previous() = stepAndPlay { BridgeQueueLogic.previous(it) }

    fun removeAt(index: Int) {
        _state.value = BridgeQueueLogic.removeAt(_state.value, index)
    }

    fun setRepeat(mode: RepeatMode) {
        _state.value = BridgeQueueLogic.setRepeat(_state.value, mode)
    }

    fun toggleShuffle() {
        _state.value = BridgeQueueLogic.toggleShuffle(_state.value)
    }

    fun clear() {
        playJob?.cancel()
        _state.value = BridgeQueueState()
    }

    private fun stepAndPlay(compute: (BridgeQueueState) -> BridgeQueueState?) {
        val result = compute(_state.value) ?: return
        _state.value = result.copy(currentFormatInfo = null, currentArtwork = null)
        playCurrent()
    }

    /**
     * Only steps a queue that actually has something current - see the class doc.
     *
     * Unlike the user-facing [next] (a manual "skip" press should just do nothing at the end of a
     * non-repeating queue and leave the last track showing), a natural end-of-queue here has to
     * relinquish control back to real HEOS state entirely. Otherwise [BridgeQueueState.currentItem]
     * - and so [PlayerUiState.isBridgeModeActive] - stays pinned to the last bridge track forever,
     * even once the receiver has moved on to whatever it does next (its own persistent queue, most
     * often), leaving Now Playing stuck showing a track that finished minutes ago.
     */
    private fun advance() {
        if (_state.value.items.isEmpty() || _state.value.currentIndex == -1) return
        // Computed once and reused (not delegated to the public next()), since BridgeQueueLogic.next
        // draws from Math.random() under shuffle - calling it a second time to re-derive the same
        // step could legitimately pick a different track, or land on null where the first call didn't.
        val result = BridgeQueueLogic.next(_state.value)
        if (result == null) {
            clear()
        } else {
            _state.value = result.copy(currentFormatInfo = null, currentArtwork = null)
            playCurrent()
        }
    }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        val events = heosSession.events ?: return
        eventsJob = scope.launch {
            events.collect { frame ->
                val isRealStop = frame.eventName == "player_state_changed" &&
                    frame.attributes["state"] == "stop" &&
                    System.currentTimeMillis() - currentTrackStartedAtMs >= STOP_TRANSITION_GUARD_MS
                if (isRealStop) advance()
            }
        }
    }

    private fun playCurrent() {
        playJob?.cancel()
        val item = _state.value.currentItem ?: return
        playJob = scope.launch {
            val playerId = heosSession.resolvePid() ?: return@launch
            val url = smbBridgeService.urlFor(item.path) ?: return@launch
            currentTrackStartedAtMs = System.currentTimeMillis()
            runCatching { heosSession.heosClient?.playStream(playerId, url) }

            val formatInfo = runCatching { smbBridgeService.formatInfoFor(item.path) }.getOrNull()
            val artwork = runCatching { smbBridgeService.artworkFor(item.path) }.getOrNull()
            // A fast next()/previous() may have moved on while this lookup was in flight.
            if (_state.value.currentItem == item) {
                _state.value = _state.value.copy(currentFormatInfo = formatInfo, currentArtwork = artwork)
            }
        }
    }

    private companion object {
        const val STOP_TRANSITION_GUARD_MS = 4_000L
    }
}
