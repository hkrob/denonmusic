package com.denonmusic.heos

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

    targets.forEachIndexed { index, target ->
        val partCriteria = if (index == 0 && !replacing) criteria else AddCriteria.AddToEnd
        addToQueue(pid = pid, sid = target.sid, cid = target.cid, mid = target.mid, criteria = partCriteria)
        onQueued(index + 1)
    }

    // Best-effort: a queue that was built correctly should not be reported as a failure just
    // because the receiver would not start (powered off, or on another input).
    if (replacing) runCatching { setPlayState(pid, PlayState.Play) }
}
