package com.denonmusic.heos

import kotlinx.serialization.Serializable

/**
 * One stop in a browse path - sid/cid plus a name to show for it. A list of these, root first, is
 * exactly what [com.denonmusic.app.browse.BrowseRepository.replaceStack] (app module) needs to land
 * a search result's container on screen with its real breadcrumb intact, not just its own level.
 */
@Serializable
data class LibraryPathEntry(val sid: String, val cid: String?, val name: String)

/**
 * One row of a whole-library search index.
 *
 * [path] is the full stack to push to land on this entry, ending at the entry itself - only
 * meaningful for a container, since a track is queued directly rather than navigated to, so it's
 * always empty there. [queueSid]/[queueCid]/[mid] are exactly [HeosClient.addToQueue]'s own
 * arguments (sid stays the enclosing container's, cid falls back to it when the item has none of its
 * own - the same rule [QueueTargetResolver] and the app's own `BrowseViewModel.queue` both use), so a
 * search result queues without re-deriving anything from its parent.
 */
data class LibraryEntry(
    val name: String,
    val artist: String?,
    val album: String?,
    val isContainer: Boolean,
    val isTrack: Boolean,
    val path: List<LibraryPathEntry>,
    val queueSid: String,
    val queueCid: String,
    val mid: String?,
)

/**
 * Walks the whole browse tree under a source, depth-first, building a flat list for a client-side
 * search index.
 *
 * Lives next to [QueueTargetResolver], which this is a sibling of - both recurse through
 * `browse/browse` - but the two serve different jobs. [QueueTargetResolver] resolves "play
 * everything under here" for one tap: it stops at the first queueable unit per branch (a playable
 * container, or that level's own tracks) and gives up past a target budget sized for one action. A
 * search index instead has to find a *named* artist, album or track anywhere in the library, so it
 * walks every container as well as every track, all the way down, with no target cap - a budget
 * built for "how much may one `addToQueue` enqueue" has no bearing on "how much is searchable".
 *
 * Reported through [onEntry] as they're found rather than returned as one list: a whole-library walk
 * can take minutes over a slow DLNA source (one round trip per folder - see the `add_to_queue`
 * timing notes in docs/local-setup.md for how slow this receiver's own folders answer), and the
 * caller needs to show progress and persist incrementally rather than hold the whole result in
 * memory until the last folder answers.
 *
 * A folder that fails to list with a *receiver-reported* error (a stale cid, a share that went away
 * mid-walk) is skipped rather than aborting the rest of the tree - the same reasoning as
 * [com.denonmusic.app.browse.BrowseRepository.restoreResolving]: one bad branch should cost that
 * branch, not the whole index. A transport failure (a dropped connection, a command timeout) is a
 * different thing entirely and is **not** swallowed - see [fetchLevel]'s own doc for why treating a
 * dead connection the same way as an empty folder is a correctness bug, not a resilience feature.
 *
 * **Measured against a real Plex-backed AVR-X4500H (2026-10-02, see docs/local-setup.md), two things
 * in Plex's own DLNA tree make a naive "walk every container" crawl unworkable, not just slow:**
 *
 * - The aggregate source's sibling of the music library is a full **Video** library, exposed as its
 *   own browsable tree (`By Starring Actor`, `By Country`, `TV Shows`, ...). A depth-first walk that
 *   happens to visit it before `Music` burns its whole run on actor and movie folders - measured:
 *   400 browse calls, 52 seconds, zero tracks found, the walk still inside `By Starring Actor`.
 * - Once inside the real music library, Plex offers **eight parallel views of the identical content**
 *   (`All Artists`, `By Album`, `By Genre`, `By Decade`, `By Year`, `By Collection`, `Recently
 *   Added`, `By Folder`). Walking all eight would index every track up to eight times over.
 *
 * [SKIP_CONTAINER_NAMES] prunes the first problem - branches that are not a music library at all, by
 * name, since nothing in the protocol itself distinguishes them (confirmed: Video, Music and Photos
 * all come back with the same generic `"type":"container"`). [VIEW_CONTAINER_NAMES] and
 * [VIEW_PREFERENCE_ORDER] solve the second: when two or more same-level siblings are recognised
 * views of one library, only one is walked - `By Folder` first, since it mirrors the real filesystem
 * 1:1 and so is the only one of the eight that cannot itself double-count a track - and the rest are
 * neither walked nor indexed, since nobody searches for a view's own name. Both lists are name
 * matches, not a protocol signal, because there isn't one; a non-Plex source (the HEOS-native SMB
 * share this app otherwise prefers) is most unlikely to have same-level folders coincidentally named
 * this way, and if one ever does, the cost is that one folder missing from search, not a wrong result.
 */
object LibraryIndexer {

    /** Source > server > artist > album > disc, plus headroom - deeper than [QueueTargetResolver]'s
     * own default since a search index has to reach every leaf, not just resolve one "play all" tap. */
    const val DEFAULT_MAX_DEPTH = 10

    /** Plex library categories that are not music at all - see the class doc. Skipped outright: not
     * walked, not indexed. */
    private val SKIP_CONTAINER_NAMES = setOf(
        "video", "videos", "photo", "photos",
        "music channels", "shared music", "remote music", "music queue",
        "music recommendations", "preferences",
    )

    /** Plex's parallel views over one music library - see the class doc. Only ever walked one at a
     * time, and never themselves indexed as a result - nobody searches for "By Folder". */
    private val VIEW_CONTAINER_NAMES = setOf(
        "all artists", "by album", "by artist", "by genre", "by decade",
        "by year", "by collection", "recently added", "by folder",
    )

    /** Which view wins when several are offered at once - see the class doc for why `by folder`
     * is first. Whichever recognised view appears first in the listing is the fallback past these two. */
    private val VIEW_PREFERENCE_ORDER = listOf("by folder", "all artists")

    /** Guards against an unbounded chain of view collapses; one real level is all that's ever been
     * observed, so this is headroom, not a tuned limit. */
    private const val MAX_VIEW_UNWRAPS = 3

    suspend fun crawl(
        client: HeosClient,
        rootSid: String,
        rootName: String,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onEntry: suspend (LibraryEntry) -> Unit,
    ) {
        crawlAtDepth(
            client,
            sid = rootSid,
            cid = null,
            path = listOf(LibraryPathEntry(rootSid, null, rootName)),
            depth = 0,
            maxDepth = maxDepth,
            onEntry = onEntry,
        )
    }

    private suspend fun crawlAtDepth(
        client: HeosClient,
        sid: String,
        cid: String?,
        path: List<LibraryPathEntry>,
        depth: Int,
        maxDepth: Int,
        onEntry: suspend (LibraryEntry) -> Unit,
    ) {
        if (depth > maxDepth) return

        var items = fetchLevel(client, sid, cid).filterNot { it.isContainer && it.matches(SKIP_CONTAINER_NAMES) }

        // Collapse Plex's parallel views of one library into a single walk - see the class doc. A
        // loop, not an if, since nothing rules out a view itself opening onto another view set; in
        // every case measured so far this runs at most once.
        var unwraps = 0
        while (unwraps < MAX_VIEW_UNWRAPS) {
            val views = items.filter { it.isContainer && it.matches(VIEW_CONTAINER_NAMES) }
            if (views.size < 2) break
            val chosen = VIEW_PREFERENCE_ORDER.firstNotNullOfOrNull { preferred ->
                views.firstOrNull { it.name.trim().equals(preferred, ignoreCase = true) }
            } ?: views.first()
            // Every view observed so far is a plain sub-container of the same source (no sid of its
            // own), so the chosen view's children are fetched under the same sid/cid this level is
            // already walking - there is nothing to switch.
            val chosenCid = chosen.sid?.let { null } ?: chosen.cid
            items = (items - views.toSet()) + fetchLevel(client, chosen.sid ?: sid, chosenCid)
            unwraps++
        }

        for (item in items) {
            if (item.isContainer) {
                // A row can carry its own sid instead of a cid - a nested source (e.g. one DLNA
                // server under the aggregate "Local Music" source) rather than a folder within the
                // current one. Same switch BrowseViewModel.open() makes for the same reason.
                val targetSid = item.sid ?: sid
                val targetCid = item.sid?.let { null } ?: item.cid
                val entryPath = path + LibraryPathEntry(targetSid, targetCid, item.name)
                onEntry(
                    LibraryEntry(
                        name = item.name,
                        artist = item.artist,
                        album = item.album,
                        isContainer = true,
                        isTrack = false,
                        path = entryPath,
                        queueSid = sid,
                        queueCid = item.cid ?: cid.orEmpty(),
                        mid = null,
                    ),
                )
                crawlAtDepth(client, targetSid, targetCid, entryPath, depth + 1, maxDepth, onEntry)
            } else if (item.isTrack) {
                onEntry(
                    LibraryEntry(
                        name = item.name,
                        artist = item.artist,
                        album = item.album,
                        isContainer = false,
                        isTrack = true,
                        path = emptyList(),
                        queueSid = sid,
                        queueCid = item.cid ?: cid.orEmpty(),
                        mid = item.mid,
                    ),
                )
            }
        }
    }

    /**
     * Catches only [HeosCommandException] - a *receiver-reported* failure for this one folder (a
     * stale cid, a share that went away), which is fine to skip and keep walking the rest of the
     * tree. Anything else - an `IOException` from a dropped socket, a command timeout - is the
     * transport itself failing, not this one folder, and must propagate rather than be read as "this
     * folder is empty": a crawl that silently treats a dead connection as thousands of empty folders
     * would finish "successfully" with a tiny fraction of the real library, and
     * [com.denonmusic.app.search.SearchIndexRepository] would then overwrite a good index with that
     * truncated one. A transport failure has to fail the whole crawl, loudly, so the caller leaves
     * the previous index in place instead.
     */
    private suspend fun fetchLevel(client: HeosClient, sid: String, cid: String?): List<BrowseItem> {
        val items = mutableListOf<BrowseItem>()
        try {
            client.browseAll(sid, cid).collect { page -> items += page.items }
        } catch (e: HeosCommandException) {
            return items
        }
        return items
    }

    private fun BrowseItem.matches(names: Set<String>) = name.trim().lowercase() in names
}
