package com.denonmusic.heos

/** One `addToQueue` call's worth of work: a whole playable container, or a single track by `mid`. */
data class QueueTarget(val sid: String, val cid: String, val mid: String?)

/**
 * Resolves "play everything under here" into the flat list of `addToQueue` calls that expresses it.
 *
 * Lives in `:core:heos` rather than next to either of its callers on purpose. Both the in-app browse
 * screen and the LAN control HTTP API need this exact walk, and for a while they each carried their
 * own copy - which is precisely how the bug documented in [collect] below came to be fixed twice,
 * separately, once per copy. One implementation here means the next fix can only happen once, and it
 * gets [HeosClient]'s own test fakes for free.
 */
object QueueTargetResolver {

    /**
     * How deep to walk before giving up. A real library nests source > artist > album > disc; six
     * leaves room for an unusual layout without letting a pathological one run away.
     */
    const val DEFAULT_MAX_RECURSE_DEPTH = 6

    /**
     * Ceiling on how much one action may enqueue, so a tap on a huge aggregate container (an artist
     * index, or a whole DLNA library root) can't silently try to enqueue thousands of tracks.
     */
    const val DEFAULT_MAX_QUEUE_TARGETS = 300

    /**
     * Recursively resolves every leaf under [sid]/[cid] into something a single `addToQueue` call can
     * take: a playable container (HEOS flattens its own tracks server-side, so one call covers a
     * whole album or disc), or, failing that, each of its tracks individually by `mid`.
     *
     * Walking the subtree rather than trusting this level's own "playable container" flag is the
     * whole point: a multi-disc album (`CD1`/`CD2` subfolders with no tracks at the album's own
     * level) never gets that flag from the server, so a plain `addToQueue(cid = cid)` silently does
     * nothing for it.
     *
     * [onProgress] is called with the running count of targets gathered across the whole walk, not
     * per level - a per-level count jumps backwards every time the walk descends into a smaller
     * subtree, which reads as progress being lost. Bounded by [maxDepth] and [maxTargets]; the returned list may
     * slightly exceed [maxTargets], since the walk stops at the first subtree that crosses it rather
     * than truncating mid-container - callers are expected to treat overshoot as "too many, refuse"
     * rather than silently queueing a partial folder.
     */
    suspend fun collect(
        client: HeosClient,
        sid: String,
        cid: String?,
        maxDepth: Int = DEFAULT_MAX_RECURSE_DEPTH,
        maxTargets: Int = DEFAULT_MAX_QUEUE_TARGETS,
        onProgress: (gathered: Int) -> Unit = {},
    ): List<QueueTarget> {
        // One counter for the whole walk, so what the caller sees only ever climbs.
        var gathered = 0
        val report: (Int) -> Unit = { added ->
            gathered += added
            onProgress(gathered)
        }
        return collectAtDepth(client, sid, cid, maxDepth, maxTargets, report, depth = 0)
    }

    private suspend fun collectAtDepth(
        client: HeosClient,
        sid: String,
        cid: String?,
        maxDepth: Int,
        maxTargets: Int,
        /** Called with how many targets this step just added, never a running total - see [collect]. */
        reportAdded: (Int) -> Unit,
        depth: Int,
    ): List<QueueTarget> {
        if (depth > maxDepth) return emptyList()

        val items = mutableListOf<BrowseItem>()
        var isPlayable = false
        client.browseAll(sid, cid).collect { page ->
            items += page.items
            isPlayable = isPlayable || page.isPlayableContainer
        }

        val subContainers = items.filter { it.isContainer && it.sid == null && it.cid != null }
        val ownTracks = items.filter { it.isTrack }
            .map { QueueTarget(sid, it.cid ?: cid.orEmpty(), it.mid) }

        if (subContainers.isEmpty()) {
            val leaf = if (isPlayable && cid != null) listOf(QueueTarget(sid, cid, null)) else ownTracks
            reportAdded(leaf.size)
            return leaf
        }

        // A folder can hold direct tracks *and* subfolders at once - not just a multi-disc album's
        // CD1/CD2, but also, confirmed live, an ordinary album folder with its tracks sitting right
        // there alongside incidental non-audio subfolders (an "art"/"tech" pair for extras). Only
        // ever recursing into subContainers here silently dropped every one of this level's own
        // tracks whenever any subfolder existed at all - "Nothing playable found" on a folder with
        // playable tracks plainly visible in the browse listing above it.
        val results = ownTracks.toMutableList()
        reportAdded(ownTracks.size)
        for (sub in subContainers) {
            results += collectAtDepth(client, sid, sub.cid, maxDepth, maxTargets, reportAdded, depth + 1)
            if (results.size > maxTargets) break
        }
        return results
    }
}
