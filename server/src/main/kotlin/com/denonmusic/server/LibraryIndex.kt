package com.denonmusic.server

import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.LibraryEntry
import com.denonmusic.heos.LibraryIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One searchable row. [sid]/[cid] address the entry itself: for a container, the folder to walk when
 * queueing it; for a track, the enclosing folder, which with [mid] is what `add_to_queue` takes.
 * [context] is everything that places the entry - artist, album and the folders above it - so a
 * query that names the artist as well as the title has something to match against.
 */
@Serializable
data class IndexedEntry(
    val name: String,
    val artist: String? = null,
    val album: String? = null,
    val kind: SearchRanking.Kind,
    val sid: String,
    val cid: String? = null,
    val mid: String? = null,
    val context: String = "",
) {
    @Transient
    val nameNorm: String = SearchRanking.normalize(name)

    @Transient
    val contextNorm: String = SearchRanking.normalize(context)
}

@Serializable
data class IndexFile(val indexedAt: Long, val entries: List<IndexedEntry>)

@Serializable
data class IndexStatus(
    val state: String,
    val count: Int,
    val indexedAt: Long? = null,
    val found: Int? = null,
    val error: String? = null,
)

/**
 * The server's whole-library search index: the same crawl the Android app runs ([LibraryIndexer],
 * unchanged), held in memory and written to one file so a restart does not cost another crawl.
 *
 * What the app needed around its crawl - Room, a foreground service, a marker for a process killed
 * partway - answers Android's lifecycle, not the receiver's, and none of it comes along. What does
 * carry over is the rule that mattered: a crawl replaces the index only when it finishes. A failed
 * or interrupted crawl leaves the previous index in place, stale rather than empty.
 */
class LibraryIndex(
    private val file: File,
    private val scope: CoroutineScope,
    private val clientProvider: () -> HeosClient?,
    private val sourceSid: suspend (HeosClient) -> String,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var entries: List<IndexedEntry> = emptyList()

    @Volatile
    private var indexedAt: Long? = null

    @Volatile
    private var found: Int? = null

    @Volatile
    private var lastError: String? = null

    private var job: Job? = null

    val status: IndexStatus
        get() = IndexStatus(
            state = when {
                found != null -> "indexing"
                indexedAt != null -> "ready"
                lastError != null -> "failed"
                else -> "empty"
            },
            count = entries.size,
            indexedAt = indexedAt,
            found = found,
            error = lastError,
        )

    /** Reads a previously saved index. A missing or unreadable file is just "no index yet". */
    fun load() {
        if (!file.isFile) return
        runCatching { json.decodeFromString<IndexFile>(file.readText()) }
            .onSuccess {
                entries = it.entries
                indexedAt = it.indexedAt
            }
            .onFailure { lastError = "Saved index could not be read: ${it.message}" }
    }

    /** @return false if a crawl is already running, which is left to finish. */
    @Synchronized
    fun startRebuild(): Boolean {
        if (job?.isActive == true) return false
        found = 0
        job = scope.launch {
            try {
                rebuild()
            } finally {
                found = null
            }
        }
        return true
    }

    /**
     * Retries the *whole* crawl on a transport failure, re-reading the client each time, for the
     * reason the app's `SearchIndexRepository.runReindex` gives: a crawl this long is likely to
     * outlast a dropped connection, the session reconnects on its own, and only a fresh client
     * benefits from that.
     */
    private suspend fun rebuild() {
        var error: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(retryDelayMillis)
            val client = clientProvider()
            if (client == null) {
                error = "Not connected to the receiver"
                return@repeat
            }
            try {
                val sid = sourceSid(client)
                val rootName = client.getMusicSources().firstOrNull { it.sid == sid }?.name ?: "Library"
                val rows = ArrayList<IndexedEntry>()
                LibraryIndexer.crawl(client, sid, rootName) { entry ->
                    rows += entry.toIndexed()
                    found = rows.size
                }
                val now = System.currentTimeMillis()
                save(IndexFile(now, rows))
                entries = rows
                indexedAt = now
                lastError = null
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // A missing source is a configuration fact, not a blip a reconnect fixes.
                lastError = e.message
                return
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
        lastError = error ?: "Indexing failed"
    }

    /** Written beside the target and moved over it, so a crash mid-write cannot leave half a file. */
    private suspend fun save(index: IndexFile) = withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absolutePath + ".tmp")
        temp.writeText(json.encodeToString(IndexFile.serializer(), index))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun search(query: String, artist: String? = null, kind: SearchRanking.Kind? = null, limit: Int = 10) =
        SearchRanking.rank(entries, query, artist, kind, limit)

    private fun LibraryEntry.toIndexed(): IndexedEntry {
        // A container's path ends at the container itself; everything before that is where it sits.
        val self = path.lastOrNull()
        val above = path.dropLast(1).drop(1).map { it.name }
        return IndexedEntry(
            name = name,
            artist = artist,
            album = album,
            kind = if (isContainer) SearchRanking.Kind.Container else SearchRanking.Kind.Track,
            sid = if (isContainer) self?.sid ?: queueSid else queueSid,
            cid = if (isContainer) self?.cid else queueCid,
            mid = mid,
            context = (listOfNotNull(artist, album) + above).joinToString(" "),
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 5_000L
    }
}
