package com.denonmusic.app.search

import com.denonmusic.app.browse.BrowseLevel
import com.denonmusic.app.browse.SourceRepository
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.search.SearchIndexEntity
import com.denonmusic.data.search.fromPathJson
import com.denonmusic.data.search.toPathJson
import com.denonmusic.heos.LibraryEntry
import com.denonmusic.heos.LibraryIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * A singleton with its own scope, not a `ViewModel` method, because a full crawl can run for minutes
 * over a large library (one round trip per folder) and must not be cancelled just because the user
 * backed out of the search screen while it was running - the same reasoning as [HeosSession] and
 * `PlayerStateTracker` owning state that outlives any one screen.
 */
@Singleton
class SearchIndexRepository @Inject constructor(
    private val session: HeosSession,
    private val sourceRepository: SourceRepository,
    private val dao: SearchIndexDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<SearchIndexStatus>(SearchIndexStatus.NeverIndexed)
    val status: StateFlow<SearchIndexStatus> = _status.asStateFlow()

    private var indexJob: Job? = null

    init {
        scope.launch {
            val count = dao.count()
            val indexedAt = dao.lastIndexedAt()
            if (count > 0 && indexedAt != null) _status.value = SearchIndexStatus.Ready(count, indexedAt)
        }
    }

    /**
     * Starts (or re-starts) a full crawl. A no-op while one is already running, rather than queued or
     * cancel-and-restart - a second tap on "reindex" while the first is still walking the tree should
     * not throw away the progress already made.
     *
     * Builds the whole result in memory and only replaces the stored index once the crawl finishes
     * (see [SearchIndexDao.replaceAll]), rather than clearing the table up front and inserting as it
     * goes: a crawl that fails or is cut off partway (the receiver reboots, Wi-Fi drops) would
     * otherwise leave search *worse* than before it was asked to refresh - empty, instead of merely
     * stale. Measured against a real library (see docs/local-setup.md, 2026-10-02): tens of thousands
     * of small rows at most, still cheap to hold in memory for the run's own duration.
     */
    fun startReindex() {
        if (indexJob?.isActive == true) return
        indexJob = scope.launch {
            val client = session.heosClient
            if (client == null) {
                _status.value = SearchIndexStatus.Failed("Not connected to the receiver")
                return@launch
            }
            val sid = sourceRepository.resolveCachedSid(client)
            if (sid == null) {
                _status.value = SearchIndexStatus.Failed("No queueable music source found")
                return@launch
            }
            // So a container result's path starts with the same breadcrumb label Browse's own root
            // level shows (see BrowseViewModel.bootstrapUnsafe) rather than a generic placeholder -
            // one extra get_music_sources call, paid once per reindex, not per search.
            val rootName = client.getMusicSources().firstOrNull { it.sid == sid }?.name ?: "Library"
            _status.value = SearchIndexStatus.Indexing(0)
            val now = System.currentTimeMillis()
            val rows = mutableListOf<SearchIndexEntity>()
            val result = runCatching {
                LibraryIndexer.crawl(client, sid, rootName) { entry ->
                    rows += entry.toEntity(now)
                    if (rows.size % PROGRESS_STEP == 0) _status.value = SearchIndexStatus.Indexing(rows.size)
                }
            }
            if (result.isFailure) {
                _status.value = SearchIndexStatus.Failed(result.exceptionOrNull()?.message ?: "Indexing failed")
                return@launch
            }
            dao.replaceAll(rows)
            _status.value = SearchIndexStatus.Ready(rows.size, now)
        }
    }

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
    }
}
