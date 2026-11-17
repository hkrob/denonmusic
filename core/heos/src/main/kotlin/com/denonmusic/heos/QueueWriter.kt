package com.denonmusic.heos

import kotlinx.coroutines.delay

/**
 * Queues a whole resolved subtree - the write half of what [QueueTargetResolver] reads. Only the
 * first part carries [criteria]; every part after it always appends, or a multi-part "replace and
 * play" would replace the queue anew on each call and leave only the last part in it. [onQueued]
 * reports parts completed, for a progress display.
 *
 * Lives here rather than with either caller because there are two of them - the Android app and the
 * headless server - and the sequencing below is receiver behaviour that was measured, not derived.
 * A second copy of it is a second place to get it wrong.
 */
suspend fun HeosClient.addAllToQueue(
    pid: String,
    targets: List<QueueTarget>,
    criteria: AddCriteria,
    onQueued: (completed: Int) -> Unit = {},
) {
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
    if (replacing) clearQueue(pid)

    // A single add - the common case - has nothing after it to collide with, and stays as fast as
    // it was. Only a run of them needs pacing; see awaitQueueSettled for what that is pacing.
    val paced = targets.size > 1
    var queued = if (paced && !replacing) runCatching { getQueueCount(pid) }.getOrNull() ?: 0 else 0

    targets.forEachIndexed { index, target ->
        val partCriteria = if (index == 0 && !replacing) criteria else AddCriteria.AddToEnd
        addToQueue(pid = pid, sid = target.sid, cid = target.cid, mid = target.mid, criteria = partCriteria)
        if (paced) queued = awaitQueueSettled(pid, above = queued)
        onQueued(index + 1)
    }

    // Best-effort: a queue that was built correctly should not be reported as a failure just
    // because the receiver would not start (powered off, or on another input).
    if (replacing) runCatching { setPlayState(pid, PlayState.Play) }
}

/**
 * Waits for the receiver to finish taking in the `add_to_queue` just sent, before anything else is
 * asked of it, and returns the queue's size once it has.
 *
 * The same asynchrony as the two notes above, and what happens when a command the receiver does
 * *not* refuse arrives inside that window. Measured on a real AVR-X4500H (2026-11-17), each from a
 * queue that was reading normally beforehand:
 *
 * - `set_play_state play` sent 10ms after the last add was accepted started playback, but every
 *   `get_queue` after it took 20.0 seconds - an interim "command under process", then the answer -
 *   and went on doing so for minutes. Sent 0.4s after, one slow `get_queue` (1.2s); a second after,
 *   or with no play at all, none.
 * - Single-track adds (`mid` given) are never refused with `eid=9` the way container adds are, so
 *   [HeosClient.addToQueue]'s retry never fires for them. Twelve sent back to back, and four, left
 *   `get_queue` taking 20 seconds in the same way with no play involved. The same twelve sent half
 *   a second apart did not. Four *containers* back to back, which the receiver paces itself by
 *   refusing, did not either.
 *
 * Twenty seconds is longer than [HeosConnection]'s command timeout, so to a caller the queue simply
 * cannot be read, until some later add happens to clear the condition. It is the receiver's fault
 * and not something a retry can see, since nothing fails.
 *
 * `get_queue` is polled because it is what was measured to work as the barrier: asked while the
 * receiver is ingesting, it either answers with the count so far or holds its answer until the
 * ingest is done (1.1-1.3s for a 13-track folder), and the count stops moving after that. So: the
 * count has passed [above], and two readings in a row agree. Bounded and best-effort - a receiver
 * that will not report its queue is still sent the rest - and a target that adds nothing (an empty
 * folder) costs the whole timeout, once.
 */
private suspend fun HeosClient.awaitQueueSettled(pid: String, above: Int): Int {
    var previous: Int? = null
    val deadline = System.nanoTime() + QUEUE_SETTLE_TIMEOUT_MILLIS * 1_000_000
    while (System.nanoTime() < deadline) {
        val count = runCatching { getQueueCount(pid) }.getOrNull() ?: return previous ?: above
        if (count > above && count == previous) return count
        previous = count
        delay(QUEUE_SETTLE_POLL_MILLIS)
    }
    return previous ?: above
}

/** Longest measured ingest was about 1.3s for one folder; this is headroom on that. */
private const val QUEUE_SETTLE_TIMEOUT_MILLIS = 5_000L
private const val QUEUE_SETTLE_POLL_MILLIS = 150L
