package com.denonmusic.app.heos

import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing [HeosPlaybackStarter] needs from the bridge queue, narrowed to a single method so a
 * one-line fake can stand in for it in tests without dragging in real sockets or an SMB service.
 */
fun interface BridgeQueueClearer {
    fun clear()
}

/**
 * Owns every "hand control of Now Playing back to real HEOS" action, so the rule that goes with them
 * can't be forgotten at a new call site.
 *
 * That rule: starting real HEOS playback must clear the bridge queue. The bridge's `play_stream`
 * tracks have no real HEOS queue behind them, so `BridgeQueueState.currentItem` is what
 * `PlayerUiState.isBridgeModeActive` reads to decide whether the app's own transport controls and
 * Now Playing follow the bridge or the receiver. Leave it set after the user has explicitly started
 * a real queue item and the UI keeps showing the finished bridge track while the receiver plays
 * something else.
 *
 * It was previously spelled out by hand at five call sites across the two control surfaces - and
 * missing from a sixth, `PlayerViewModel.playQueueItem`, the direct analogue of the LAN API's
 * `/queue/play`, which did have it. Baking it into the wrapper means the seventh call site gets it
 * for free.
 *
 * [starting] clears only after [action] returns normally: a failed `addToQueue` never started real
 * playback, so relinquishing bridge ownership there would leave nothing driving Now Playing at all.
 */
@Singleton
class HeosPlaybackStarter @Inject constructor(private val bridgeQueue: BridgeQueueClearer) {

    /** Runs [action], then relinquishes bridge-mode ownership. Propagates whatever [action] throws. */
    suspend fun <T> starting(action: suspend () -> T): T {
        val result = action()
        bridgeQueue.clear()
        return result
    }

    suspend fun addToQueue(
        client: HeosClient,
        pid: String,
        sid: String,
        cid: String,
        mid: String?,
        criteria: AddCriteria,
    ) = starting { client.addToQueue(pid = pid, sid = sid, cid = cid, mid = mid, criteria = criteria) }

    /**
     * Queues a whole resolved subtree. Only the first part carries [criteria]; every part after it
     * always appends, or a multi-part "replace and play" would replace the queue anew on each call
     * and leave only the last part in it. [onQueued] reports parts completed, for a progress display.
     *
     * The bridge queue is cleared once at the end rather than per part - clearing per part would just
     * re-clear an already-empty queue on every iteration after the first.
     */
    suspend fun addAll(
        client: HeosClient,
        pid: String,
        targets: List<QueueTarget>,
        criteria: AddCriteria,
        onQueued: (completed: Int) -> Unit = {},
    ) = starting {
        // `aid=4` means "replace the queue and play", and the receiver carries it out in its own
        // time. Measured on a real AVR-X4500H: sent alone it replaces correctly, but with a second
        // add arriving straight after it, *both* halves of it are lost - the old queue survives and
        // the new parts are merely appended, and playback never starts. That is the whole of the
        // "play all queued the album but played nothing" report, and it also silently appended to
        // whatever was queued before instead of replacing it.
        //
        // So a multi-part replace does not ask for aid=4 at all. Clearing the queue and appending
        // every part is the same intent expressed in steps the receiver cannot half-apply, and the
        // play at the end then always starts the first track of what was just queued, because the
        // queue began empty. Verified against the receiver.
        val replacing = criteria == AddCriteria.ReplaceAndPlay && targets.size > 1
        if (replacing) client.clearQueue(pid)

        targets.forEachIndexed { index, target ->
            val partCriteria = if (index == 0 && !replacing) criteria else AddCriteria.AddToEnd
            client.addToQueue(pid = pid, sid = target.sid, cid = target.cid, mid = target.mid, criteria = partCriteria)
            onQueued(index + 1)
        }

        // Best-effort: a queue that was built correctly should not be reported as a failure just
        // because the receiver would not start (powered off, or on another input).
        if (replacing) runCatching { client.setPlayState(pid, PlayState.Play) }
    }

    suspend fun playQueueItem(client: HeosClient, pid: String, qid: Int) =
        starting { client.playQueueItem(pid, qid) }
}
