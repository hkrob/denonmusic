package com.denonmusic.server

import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.HeosCommandException
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueCollection
import com.denonmusic.heos.QueueTarget
import com.denonmusic.heos.QueueTargetResolver
import com.denonmusic.heos.addAllToQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the language model is asked to produce from an utterance - see docs/home-assistant. */
@Serializable
data class IntentRequest(
    val action: String,
    val query: String? = null,
    val artist: String? = null,
    val type: String? = null,
)

/**
 * Everything the routes do, kept apart from Ktor so it is one place to read and to test. Each
 * method either returns the JSON to send or throws [ApiException].
 */
class Controller(
    private val config: Config,
    private val receiver: Receiver,
    private val index: LibraryIndex,
) {

    // -- state -------------------------------------------------------------

    fun health(): JsonObject = buildJsonObject {
        put("heos", receiver.heos.state.value::class.simpleName)
        put("avr", receiver.avr.state.value::class.simpleName)
        put("index", index.status.state)
        put("indexed", index.status.count)
    }

    /**
     * Asked of the receiver on every call rather than tracked from events, as the app's own
     * `/status` does. Each field fails on its own: a receiver in standby still answers some of
     * these, and one missing answer should cost that field, not the response.
     */
    suspend fun status(): JsonObject = coroutineScope {
        val player = receiver.player()
        val client = player.client
        val pid = player.pid
        val avr = receiver.avr.avrClient

        val nowPlaying = async { runCatching { client.getNowPlaying(pid) }.getOrNull() }
        val playState = async { runCatching { client.getPlayState(pid) }.getOrNull() }
        val volume = async { runCatching { client.getVolume(pid) }.getOrNull() }
        val power = async { avr?.let { runCatching { it.powerState() }.getOrNull() } }
        val input = async { avr?.let { runCatching { it.inputSource() }.getOrNull() } }
        val soundMode = async { avr?.let { runCatching { it.soundMode() }.getOrNull() } }
        val muted = async { avr?.let { runCatching { it.isMuted() }.getOrNull() } }

        buildJsonObject {
            put("song", nowPlaying.await()?.song)
            put("artist", nowPlaying.await()?.artist)
            put("album", nowPlaying.await()?.album)
            put("imageUrl", nowPlaying.await()?.imageUrl)
            put("playState", playState.await()?.wire)
            put("volume", volume.await())
            put("muted", muted.await())
            put("avrPower", power.await()?.name)
            put("input", input.await())
            put("soundMode", soundMode.await()?.name)
        }
    }

    // -- transport and volume ----------------------------------------------

    suspend fun setPlayState(state: PlayState): JsonObject {
        val (client, pid) = receiver.player()
        client.setPlayState(pid, state)
        return ok()
    }

    suspend fun next(): JsonObject {
        val (client, pid) = receiver.player()
        client.playNext(pid)
        return ok()
    }

    suspend fun previous(): JsonObject {
        val (client, pid) = receiver.player()
        client.playPrevious(pid)
        return ok()
    }

    /** HEOS volume, 0-100, never above [Config.maxVolume]. Reports the level actually sent. */
    suspend fun setVolume(level: Int): JsonObject {
        val (client, pid) = receiver.player()
        val applied = level.coerceIn(0, config.maxVolume)
        client.setVolume(pid, applied)
        return ok { put("volume", applied) }
    }

    suspend fun changeVolume(delta: Int): JsonObject {
        val (client, pid) = receiver.player()
        val current = client.getVolume(pid) ?: throw ApiException(502, "the receiver did not report its volume")
        val applied = (current + delta).coerceIn(0, config.maxVolume)
        // A level already above the ceiling - set at the receiver itself - is not pulled down by a
        // request to go *up*; only ever move in the direction that was asked for.
        if (delta > 0 && applied <= current) return ok { put("volume", current) }
        client.setVolume(pid, applied)
        return ok { put("volume", applied) }
    }

    suspend fun setMute(on: Boolean): JsonObject {
        val (client, pid) = receiver.player()
        client.setMute(pid, on)
        return ok()
    }

    suspend fun setPower(on: Boolean): JsonObject {
        val avr = receiver.avrClient()
        if (on) avr.powerOn() else avr.powerStandby()
        return ok()
    }

    // -- queue -------------------------------------------------------------

    suspend fun queue() = buildJsonArray {
        val (client, pid) = receiver.player()
        client.getQueue(pid).forEach { item ->
            add(
                buildJsonObject {
                    put("qid", item.qid)
                    put("song", item.song)
                    put("artist", item.artist)
                    put("album", item.album)
                },
            )
        }
    }

    suspend fun playQueueItem(qid: Int): JsonObject {
        val (client, pid) = receiver.player()
        client.playQueueItem(pid, qid)
        return ok()
    }

    suspend fun clearQueue(): JsonObject {
        val (client, pid) = receiver.player()
        client.clearQueue(pid)
        return ok()
    }

    /**
     * Queues a track ([mid] given) or everything under a container.
     *
     * A container is always walked with [QueueTargetResolver] rather than handed to `add_to_queue`
     * as it is: a multi-disc album has no tracks at its own level, and the app's own comments say a
     * plain add of one queues nothing (not re-measured here). The walk costs a browse per folder; it is what makes "play
     * this artist" work at all.
     *
     * @return how many `add_to_queue` targets were sent.
     */
    suspend fun addToQueue(sid: String, cid: String?, mid: String?, criteria: AddCriteria): Int {
        if (mid != null) {
            val (client, pid) = receiver.player()
            client.addAllToQueue(pid, listOf(QueueTarget(sid, cid.orEmpty(), mid)), criteria)
            return 1
        }
        return addFoldersToQueue(listOf(sid to cid), criteria)
    }

    /**
     * Queues several folders as one action - an album spread over disc folders, or an artist. Every
     * folder is resolved before anything is queued, so being over the limit costs nothing but the
     * browsing, and the whole lot goes through one [addAllToQueue] and therefore one replace.
     */
    private suspend fun addFoldersToQueue(folders: List<Pair<String, String?>>, criteria: AddCriteria): Int {
        val (client, pid) = receiver.player()
        val limit = QueueTargetResolver.DEFAULT_MAX_QUEUE_TARGETS
        val targets = ArrayList<QueueTarget>()
        for ((sid, cid) in folders) {
            when (val collected = QueueTargetResolver.collect(client, sid, cid, maxTargets = limit - targets.size)) {
                is QueueCollection.TooMany ->
                    throw ApiException(400, "too many items to queue at once (limit $limit) - ask for something smaller")
                is QueueCollection.Complete -> targets += collected.targets
            }
        }
        if (targets.isEmpty()) throw ApiException(404, "nothing playable found there")
        client.addAllToQueue(pid, targets, criteria)
        return targets.size
    }

    // -- search and intent -------------------------------------------------

    fun search(query: String, artist: String?, type: String?, limit: Int): JsonObject {
        val hits = index.search(query, artist, parseKind(type), limit.coerceIn(1, MAX_SEARCH_LIMIT))
        return buildJsonObject {
            put("index", index.status.state)
            putJsonArray("results") { hits.forEach { add(it.toJson()) } }
        }
    }

    /**
     * One spoken command, already reduced to `{action, query}` by the language model. The reply
     * carries a `speech` line so the caller can say what happened without composing it.
     */
    suspend fun intent(request: IntentRequest): JsonObject {
        val action = request.action.trim().lowercase()
        val query = request.query?.trim().orEmpty()
        return when (action) {
            "play", "play_now", "play_next", "queue" -> {
                // "Play" with nothing to look for means "carry on".
                if (query.isEmpty() && action == "play") return spoken(setPlayState(PlayState.Play), "Resuming")
                if (query.isEmpty()) throw ApiException(400, "'$action' needs a query")
                playFromSearch(action, query, request.artist?.takeIf { it.isNotBlank() }, request.type)
            }
            "resume" -> spoken(setPlayState(PlayState.Play), "Resuming")
            "pause" -> spoken(setPlayState(PlayState.Pause), "Paused")
            "stop" -> spoken(setPlayState(PlayState.Stop), "Stopped")
            "next" -> spoken(next(), "Next track")
            "previous" -> spoken(previous(), "Previous track")
            "volume_up" -> changeVolume(config.volumeStep).let { spoken(it, "Volume ${it["volume"]}") }
            "volume_down" -> changeVolume(-config.volumeStep).let { spoken(it, "Volume ${it["volume"]}") }
            "set_volume" -> {
                val level = query.toIntOrNull() ?: throw ApiException(400, "'set_volume' needs a number as its query")
                setVolume(level).let { spoken(it, "Volume ${it["volume"]}") }
            }
            "mute" -> spoken(setMute(true), "Muted")
            "unmute" -> spoken(setMute(false), "Unmuted")
            else -> throw ApiException(400, "unknown action '$action', expected one of ${ACTIONS.joinToString()}")
        }
    }

    private suspend fun playFromSearch(action: String, query: String, artist: String?, type: String?): JsonObject {
        if (index.status.count == 0) {
            throw ApiException(503, "the library index is ${index.status.state} - POST /index/rebuild and wait for it")
        }
        val hits = index.search(query, artist, parseKind(type), limit = 5)
        val best = hits.firstOrNull() ?: throw ApiException(404, "nothing in the library matched '$query'")
        val criteria = when (action) {
            "play" -> AddCriteria.ReplaceAndPlay
            "play_now" -> AddCriteria.PlayNow
            "play_next" -> AddCriteria.PlayNext
            else -> AddCriteria.AddToEnd
        }
        val entry = best.entry
        // An artist can be hundreds of folders - 610 for one on the library this was first run
        // against - and each costs a browse and an add the receiver takes its time over. A spoken
        // request gets the first few, promptly, and is told so, rather than minutes of silence.
        val folders = entry.folders.take(MAX_FOLDERS_PER_REQUEST)
        val queued = try {
            if (folders.isNotEmpty()) {
                addFoldersToQueue(folders.map { it.sid to it.cid }, criteria)
            } else {
                addToQueue(entry.sid, entry.cid, entry.mid, criteria)
            }
        } catch (e: HeosCommandException) {
            // The ids in the index are only as good as the receiver's current view of the library.
            throw ApiException(502, "the receiver refused '${entry.name}' (${e.message}) - the index may be stale")
        }
        val verb = when (criteria) {
            AddCriteria.ReplaceAndPlay, AddCriteria.PlayNow -> "Playing"
            AddCriteria.PlayNext -> "Playing next:"
            AddCriteria.AddToEnd -> "Queued"
        }
        val partial = if (folders.size < entry.folders.size) ", the first ${folders.size} of ${entry.folders.size} albums" else ""
        return buildJsonObject {
            put("ok", true)
            put("action", action)
            put("speech", "$verb ${entry.name}" + (entry.artist?.let { " by $it" }.orEmpty()) + partial)
            put("queued", queued)
            put("truncated", partial.isNotEmpty())
            put("chosen", best.toJson())
            putJsonArray("alternatives") { hits.drop(1).forEach { add(it.toJson()) } }
        }
    }

    // -- index -------------------------------------------------------------

    fun indexStatus(): JsonObject = index.status.let { status ->
        buildJsonObject {
            put("state", status.state)
            put("count", status.count)
            put("indexedAt", status.indexedAt)
            put("found", status.found)
            put("error", status.error)
        }
    }

    fun rebuildIndex(): JsonObject = buildJsonObject {
        put("ok", true)
        put("started", index.startRebuild())
    }

    // -- helpers -----------------------------------------------------------

    private fun parseKind(type: String?): SearchRanking.Kind? = when (type?.trim()?.lowercase()) {
        null, "", "any" -> null
        "track", "song" -> SearchRanking.Kind.Track
        "album", "record" -> SearchRanking.Kind.Album
        "artist", "band" -> SearchRanking.Kind.Artist
        "folder", "container" -> SearchRanking.Kind.Container
        else -> throw ApiException(400, "unknown type '$type', expected track, album, artist, folder or any")
    }

    private fun SearchRanking.Scored.toJson() = buildJsonObject {
        put("name", entry.name)
        put("artist", entry.artist)
        put("album", entry.album)
        put("kind", entry.kind.name.lowercase())
        put("score", score)
        put("sid", entry.sid)
        put("cid", entry.cid)
        put("mid", entry.mid)
        // Only on an album or artist, which is the folders its tracks are in rather than one cid.
        if (entry.folders.isNotEmpty()) {
            putJsonArray("folders") {
                entry.folders.forEach { folder ->
                    add(
                        buildJsonObject {
                            put("sid", folder.sid)
                            put("cid", folder.cid)
                        },
                    )
                }
            }
        }
    }

    private fun ok(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", true)
        extra()
    }

    private fun spoken(result: JsonObject, speech: String) = JsonObject(result + ("speech" to JsonPrimitive(speech)))

    companion object {
        const val MAX_SEARCH_LIMIT = 50

        /** How many folders of an album or artist one spoken request queues - see [playFromSearch]. */
        const val MAX_FOLDERS_PER_REQUEST = 25

        val ACTIONS = listOf(
            "play", "play_now", "play_next", "queue", "resume", "pause", "stop", "next", "previous",
            "volume_up", "volume_down", "set_volume", "mute", "unmute",
        )
    }
}
