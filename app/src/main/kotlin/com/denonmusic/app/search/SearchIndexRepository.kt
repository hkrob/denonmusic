package com.denonmusic.app.search

import com.denonmusic.app.browse.BrowseLevel
import com.denonmusic.app.browse.SourceRepository
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.search.SearchIndexEntity
import com.denonmusic.data.search.fromPathJson
import com.denonmusic.data.search.toPathJson
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.LibraryEntry
import com.denonmusic.heos.LibraryIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SearchIndexStatus {
    data object NeverIndexed : SearchIndexStatus
    data class Indexing(val found: Int) : SearchIndexStatus
    data class Ready(val count: Int, val indexedAt: Long) : SearchIndexStatus
    data class Failed(val message: String) : SearchIndexStatus
}

/** One search hit: a container to navigate to, or a track to queue - never both. */
data class SearchResult(
    val name: String,
    val artist: String?,
    val album: String?,
    val isContainer: Boolean,
    val isTrack: Boolean,
    val path: List<BrowseLevel>,
    val queueSid: String,
    val queueCid: String,
    val mid: String?,
)

/**
 * Owns the whole-library search index: a client-side crawl of the queueable source's browse tree
 * (see [LibraryIndexer] for why this has to be client-side at all - the receiver's own
 * `browse/search` was probed against the real AVR-X4500H and found broken, see docs/local-setup.md),
 * cached in Room so it survives the screen that asked for it and doesn't force a re-crawl on every
 * app launch.
 *
 * Does **not** run [runReindex] on a scope of its own - a full crawl over a large library takes long
 * enough (tens of minutes - measured, see docs/local-setup.md 2026-10-02) that a plain singleton
 * scope is not enough to survive it: Android kills ordinary background work on that timescale the
 * moment the app isn't in the foreground. That is the likeliest explanation for reports of "it runs
 * for a while then just says not indexed" against a large library, with no error shown - a process
 * killed mid-crawl leaves nothing to catch - though an uncaught exception reaching the app's own
 * thread handler says the same thing on relaunch and was a real, separate bug in the code this
 * replaced (see [runReindex]'s own doc for the fix). [SearchIndexService] is what actually calls
 * [runReindex] now, from inside a foreground service, for the one reason a foreground service exists
 * at all: to not be one of the things Android is willing to kill.
 */
@Singleton
class SearchIndexRepository @Inject constructor(
    private val session: HeosSession,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val dao: SearchIndexDao,
) {

    /** Only for the one-shot restore below - [runReindex] runs on its caller's own scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<SearchIndexStatus>(SearchIndexStatus.NeverIndexed)
    val status: StateFlow<SearchIndexStatus> = _status.asStateFlow()

    init {
        scope.launch {
            val count = dao.count()
            val indexedAt = dao.lastIndexedAt()
            if (count > 0 && indexedAt != null) {
                _status.value = SearchIndexStatus.Ready(count, indexedAt)
                return@launch
            }
            // No index to show, and SearchIndexStatus itself is in-memory state that always starts
            // back at NeverIndexed on a fresh process - the only way to tell "never tried" apart from
            // "was tried, and something ended this process before it could say how" is this marker,
            // written at the start of runReindex and cleared at every one of its own terminal states.
            // Still set here means the previous attempt is one of those unexplained endings.
            if (settings.settings.first().pendingReindexStartedAt != null) {
                _status.value = SearchIndexStatus.Failed(
                    "The last build didn't finish - the app may have closed, lost its connection, " +
                        "or run low on memory partway through. Try again.",
                )
                settings.setPendingReindexStartedAt(null)
            }
        }
    }

    /**
     * Runs one full crawl to completion (or failure) and updates [status] throughout. The caller -
     * [SearchIndexService] - owns whether this is allowed to start at all (it won't call this twice
     * concurrently) and what scope it runs on; this function just does the work.
     *
     * Builds the whole result in memory and only replaces the stored index once the crawl finishes
     * (see [SearchIndexDao.replaceAll]), rather than clearing the table up front and inserting as it
     * goes: a crawl that fails or is cut off partway (the receiver reboots, Wi-Fi drops) would
     * otherwise leave search *worse* than before it was asked to refresh - empty, instead of merely
     * stale. A 280-second sample against a real library (see docs/local-setup.md, 2026-10-02) found
     * roughly 17 rows/second, which extrapolates to tens of thousands of small rows for a library
     * that takes tens of minutes to crawl - extrapolated, not measured end to end, since no crawl has
     * yet been timed to completion. If a library turns out large enough that holding every row in
     * memory until the end is itself the problem, this needs to become incremental (write in
     * batches, swap in atomically on success) rather than all-at-once; nothing here has needed that
     * yet.
     *
     * Retries a *whole* failed crawl, not a folder within it, up to [MAX_ATTEMPTS] times - re-reading
     * [HeosSession.heosClient] on each attempt rather than reusing the one from the first. A crawl
     * this long is likely to outlast at least one of the transient drops CLAUDE.md already documents
     * (Home Assistant's own `denonavr` integration resetting this exact port), and [HeosSession]
     * reconnects with its own backoff independently of this call - but only a *fresh* `heosClient`
     * benefits from that; the one this function already holds is permanently dead the moment its
     * socket is. [LibraryIndexer] has no notion of resuming a partial walk, so a retry here means
     * starting the walk over, which is still cheaper than failing the whole many-minutes operation
     * over one blip - unless blips are frequent enough that three whole-crawl restarts take over an
     * hour between them to say so. A smarter version would retry the one folder that failed, inside
     * [LibraryIndexer] itself, after waiting for [HeosSession] to reconnect - that needs the indexer
     * to ask for a fresh client itself rather than being handed one fixed for the whole walk, which
     * is more invasive than this fix needed to be for the report that prompted it. Left as a known
     * gap, not a silent one.
     */
    suspend fun runReindex() {
        // Cleared in every branch below via `finally` - see AppSettings.pendingReindexStartedAt for
        // what reads this back and why.
        settings.setPendingReindexStartedAt(System.currentTimeMillis())
        try {
            runReindexAttempts()
        } catch (e: CancellationException) {
            // Must be rethrown, not swallowed - this is a real cancellation (the service timing out,
            // or being torn down), not an ordinary failure, and eating it here would leave this
            // coroutine looking like it finished normally when it didn't.
            _status.value = SearchIndexStatus.Failed("Cancelled")
            throw e
        } finally {
            settings.setPendingReindexStartedAt(null)
        }
    }

    private suspend fun runReindexAttempts() {
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MILLIS)
            val client = session.heosClient
            if (client == null) {
                lastError = "Not connected to the receiver"
                return@repeat
            }
            // Everything that can throw for this attempt - including get_music_sources and the final
            // DB write - lives inside this one runCatching. Before this, any of them escaping
            // uncaught didn't just fail this reindex, it crashed the whole app (and with it, the
            // playback notification) - the SupervisorJob on SearchIndexService's scope stops that
            // from taking down anything else, but nothing stops an uncaught exception reaching the
            // thread's own handler otherwise.
            val outcome = runCatching {
                val sid = sourceRepository.resolveCachedSid(client) ?: throw NoQueueableSourceException()
                // So a container result's path starts with the same breadcrumb label Browse's own
                // root level shows (see BrowseViewModel.bootstrapUnsafe) rather than a generic
                // placeholder - one extra get_music_sources call, paid once per attempt, not per
                // search.
                val rootName = client.getMusicSources().firstOrNull { it.sid == sid }?.name ?: "Library"
                _status.value = SearchIndexStatus.Indexing(0)
                val now = System.currentTimeMillis()
                val rows = mutableListOf<SearchIndexEntity>()
                LibraryIndexer.crawl(client, sid, rootName) { entry ->
                    rows += entry.toEntity(now)
                    if (rows.size % PROGRESS_STEP == 0) _status.value = SearchIndexStatus.Indexing(rows.size)
                }
                dao.replaceAll(rows)
                rows.size to now
            }
            outcome.onSuccess { (count, now) ->
                _status.value = SearchIndexStatus.Ready(count, now)
                return
            }
            val error = outcome.exceptionOrNull()!!
            if (error is CancellationException) throw error
            if (error is NoQueueableSourceException) {
                // Not retried - a missing source is a configuration fact, not a blip that a
                // reconnect fixes, unlike everything else that can land here.
                _status.value = SearchIndexStatus.Failed(error.message!!)
                return
            }
            lastError = error.message ?: "Indexing failed"
        }
        _status.value = SearchIndexStatus.Failed(lastError ?: "Indexing failed")
    }

    private class NoQueueableSourceException : RuntimeException("No queueable music source found")

    suspend fun search(query: String): List<SearchResult> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        return dao.search(needle.lowercase()).map { it.toResult() }
    }

    private fun LibraryEntry.toEntity(indexedAt: Long) = SearchIndexEntity(
        name = name,
        nameLower = name.lowercase(),
        artist = artist,
        album = album,
        isContainer = isContainer,
        isTrack = isTrack,
        pathJson = path.toPathJson(),
        queueSid = queueSid,
        queueCid = queueCid,
        mid = mid,
        indexedAt = indexedAt,
    )

    private fun SearchIndexEntity.toResult() = SearchResult(
        name = name,
        artist = artist,
        album = album,
        isContainer = isContainer,
        isTrack = isTrack,
        path = pathJson.fromPathJson().map { BrowseLevel(it.sid, it.cid, it.name) },
        queueSid = queueSid,
        queueCid = queueCid,
        mid = mid,
    )

    private companion object {
        /** How often a running crawl updates [status] - every row would be a lot of StateFlow churn
         * for no visible benefit at one-row granularity. */
        const val PROGRESS_STEP = 25

        /** Whole-crawl retries on a transport failure - see [runReindex]'s own doc. */
        const val MAX_ATTEMPTS = 3

        /** [HeosSession]'s own reconnect backoff starts at 500ms and grows; this gives at least the
         * first reconnect attempt time to land before this retries with whatever client is current. */
        const val RETRY_DELAY_MILLIS = 5_000L
    }
}
