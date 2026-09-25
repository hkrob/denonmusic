package com.denonmusic.heos

import kotlinx.coroutines.flow.takeWhile

/** One `addToQueue` call's worth of work: a whole playable container, or a single track by `mid`. */
data class QueueTarget(val sid: String, val cid: String, val mid: String?)

/** The outcome of a [QueueTargetResolver.collect] walk. */
sealed interface QueueCollection {

    /** The subtree was walked to the end, and this is everything queueable in it. */
    data class Complete(val targets: List<QueueTarget>) : QueueCollection

    /**
     * The subtree is bigger than [limit], so the walk gave up part way and gathered nothing usable.
     *
     * [foundSoFar] is what had been counted at that point - a lower bound on the real size, not a
     * total, and only good for telling the user roughly how far over they are.
     */
    data class TooMany(val limit: Int, val foundSoFar: Int) : QueueCollection
}

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
     * subtree, which reads as progress being lost.
     *
     * Bounded by [maxDepth] and [maxTargets]. Crossing [maxTargets] returns [QueueCollection.TooMany]
     * and abandons the walk **at that moment**, rather than finishing it and letting the caller
     * notice the size afterwards: over a slow DLNA source every folder costs a round trip, so the
     * old "gather everything, then refuse" order left the user watching a spinner for minutes before
     * being told no. See [Budget] for the two things that cut the walk short.
     */
    suspend fun collect(
        client: HeosClient,
        sid: String,
        cid: String?,
        maxDepth: Int = DEFAULT_MAX_RECURSE_DEPTH,
        maxTargets: Int = DEFAULT_MAX_QUEUE_TARGETS,
        onProgress: (gathered: Int) -> Unit = {},
    ): QueueCollection {
        // One counter for the whole walk, so what the caller sees only ever climbs.
        val budget = Budget(maxTargets, onProgress)
        return try {
            QueueCollection.Complete(collectAtDepth(client, sid, cid, maxDepth, budget, depth = 0))
        } catch (_: BudgetExceeded) {
            QueueCollection.TooMany(limit = maxTargets, foundSoFar = budget.gathered)
        }
    }

    /**
     * Running total for one walk, and the thing that stops it.
     *
     * Unwinding by exception rather than by checking a flag after every recursive call is what makes
     * "stop" mean *now*: the alternative has each level in the stack finish its current loop first,
     * which on a wide tree is another browse per folder all the way back up. [BudgetExceeded] never
     * leaves [collect].
     */
    private class Budget(val max: Int, private val onProgress: (Int) -> Unit) {
        var gathered = 0
            private set

        /** Counts targets actually resolved, and reports them. */
        fun charge(count: Int) {
            if (count <= 0) return
            gathered += count
            onProgress(gathered)
            if (gathered > max) throw BudgetExceeded()
        }

        /**
         * Gives up before walking [containers] subfolders that cannot possibly fit.
         *
         * An estimate, deliberately: a subfolder usually yields at least one target but can yield
         * none (an "Artwork" folder of images, say), so this can refuse a container that would in
         * fact have fitted. It only ever *refuses* - it can never cause a partial queue - and the
         * alternative is paying one network round trip per folder to prove what the count already
         * makes obvious.
         */
        fun chargeProspective(containers: Int) {
            if (gathered + containers > max) throw BudgetExceeded()
        }

        /** True once this level alone has listed more items than the walk could ever accept. */
        fun cannotFit(itemsSoFar: Int): Boolean = gathered + itemsSoFar > max
    }

    private class BudgetExceeded : RuntimeException(null, null, false, false)

    private suspend fun collectAtDepth(
        client: HeosClient,
        sid: String,
        cid: String?,
        maxDepth: Int,
        budget: Budget,
        depth: Int,
    ): List<QueueTarget> {
        if (depth > maxDepth) return emptyList()

        val items = mutableListOf<BrowseItem>()
        var isPlayable = false
        // Stop paging as soon as this level alone has overrun the budget. A flat container of
        // thousands of tracks would otherwise page through all of them - 50 rows per round trip -
        // only to be refused at the end.
        client.browseAll(sid, cid)
            .takeWhile { !budget.cannotFit(items.size) }
            .collect { page ->
                items += page.items
                isPlayable = isPlayable || page.isPlayableContainer
            }

        val subContainers = items.filter { it.isContainer && it.sid == null && it.cid != null }
        val ownTracks = items.filter { it.isTrack }
            .map { QueueTarget(sid, it.cid ?: cid.orEmpty(), it.mid) }

        if (subContainers.isEmpty()) {
            val leaf = if (isPlayable && cid != null) listOf(QueueTarget(sid, cid, null)) else ownTracks
            budget.charge(leaf.size)
            return leaf
        }

        // A folder can hold direct tracks *and* subfolders at once - not just a multi-disc album's
        // CD1/CD2, but also, confirmed live, an ordinary album folder with its tracks sitting right
        // there alongside incidental non-audio subfolders (an "art"/"tech" pair for extras). Only
        // ever recursing into subContainers here silently dropped every one of this level's own
        // tracks whenever any subfolder existed at all - "Nothing playable found" on a folder with
        // playable tracks plainly visible in the browse listing above it.
        val results = ownTracks.toMutableList()
        budget.charge(ownTracks.size)
        budget.chargeProspective(subContainers.size)
        for (sub in subContainers) {
            results += collectAtDepth(client, sid, sub.cid, maxDepth, budget, depth + 1)
        }
        return results
    }
}
