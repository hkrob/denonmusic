package com.denonmusic.app.notification

import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.player.PlayerUiState
import com.denonmusic.heos.PlayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the notification shade needs to know, reduced from [PlayerUiState] to the few fields a media
 * notification actually shows. Kept separate so the service never depends on the full UI state, and
 * so an update that changes nothing visible in the shade doesn't rebuild the notification.
 */
data class NowPlayingSnapshot(
    val pid: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBridgeMode: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
) {
    /** Nothing to show: no track, from either the real HEOS queue or the bridge. */
    val isEmpty: Boolean get() = title.isNullOrBlank()
}

/**
 * The bridge between the app's player state and the notification-shade controls.
 *
 * A singleton rather than part of `PlayerViewModel` because the service that draws the notification
 * outlives any one screen, and because the transport calls belong in exactly one place: the view
 * model delegates its own play/pause/next/stop here rather than keeping a parallel copy, so the
 * shade and the in-app controls can never drift apart in behaviour.
 *
 * Note what this deliberately does *not* do: poll. It mirrors whatever `PlayerViewModel` last
 * published, because that view model already owns the receiver's event subscription and resync
 * loop, and a second poller would double the traffic to a receiver that is demonstrably sensitive
 * to being talked over (see `HeosClient.addToQueue`). The consequence is that the notification is
 * only as alive as the app: see [NowPlayingService] for what happens when the app goes away.
 */
@Singleton
class NowPlayingNotifier @Inject constructor(
    private val session: HeosSession,
    private val bridgeQueueController: BridgeQueueController,
) {

    private val _snapshot = MutableStateFlow(NowPlayingSnapshot())
    val snapshot: StateFlow<NowPlayingSnapshot> = _snapshot.asStateFlow()

    /** Called by `PlayerViewModel` whenever its state changes; cheap when nothing visible moved. */
    fun publish(state: PlayerUiState) {
        val bridgeTrack = state.bridgeQueue.currentItem
        val next = if (state.isBridgeModeActive) {
            NowPlayingSnapshot(
                pid = state.pid,
                title = bridgeTrack?.displayName,
                artist = state.nowPlaying?.artist,
                album = state.nowPlaying?.album,
                artUrl = state.nowPlaying?.imageUrl,
                isPlaying = state.playState == PlayState.Play,
                isBridgeMode = true,
                positionMillis = state.progress.positionMillis,
                durationMillis = state.progress.durationMillis,
            )
        } else {
            NowPlayingSnapshot(
                pid = state.pid,
                title = state.nowPlaying?.song,
                artist = state.nowPlaying?.artist,
                album = state.nowPlaying?.album,
                artUrl = state.nowPlaying?.imageUrl,
                isPlaying = state.playState == PlayState.Play,
                isBridgeMode = false,
                positionMillis = state.progress.positionMillis,
                durationMillis = state.progress.durationMillis,
            )
        }
        _snapshot.update { next }
    }

    /** Clears the shade - the app is shutting down and can no longer keep the controls honest. */
    fun clear() = _snapshot.update { NowPlayingSnapshot() }

    // -- transport, shared with PlayerViewModel so there is only one implementation ---------

    suspend fun setPlayState(state: PlayState) {
        val client = session.heosClient ?: return
        val pid = _snapshot.value.pid ?: return
        runCatching { client.setPlayState(pid, state) }
    }

    /**
     * In bridge mode the receiver is playing a single `play_stream` with no queue behind it, so
     * "next" has to step this app's own client-side queue instead of the receiver's.
     */
    suspend fun playNext() {
        if (_snapshot.value.isBridgeMode) return bridgeQueueController.next()
        val client = session.heosClient ?: return
        val pid = _snapshot.value.pid ?: return
        runCatching { client.playNext(pid) }
    }

    suspend fun playPrevious() {
        if (_snapshot.value.isBridgeMode) return bridgeQueueController.previous()
        val client = session.heosClient ?: return
        val pid = _snapshot.value.pid ?: return
        runCatching { client.playPrevious(pid) }
    }
}
