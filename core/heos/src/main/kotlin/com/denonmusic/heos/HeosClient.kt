package com.denonmusic.heos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Typed wrapper over [HeosConnection] for the commands this app actually uses.
 *
 * Everything here is a thin, faithful mapping onto the wire protocol. The one piece of real logic is
 * [browseAll], which has to cope with containers whose size the receiver does not know.
 */
class HeosClient(private val connection: HeosConnection) {

    // -- system ------------------------------------------------------------

    suspend fun registerForChangeEvents(enable: Boolean = true) =
        connection.command("system", "register_for_change_events", listOf("enable" to if (enable) "on" else "off"))

    suspend fun heartBeat() = connection.command("system", "heart_beat")

    // -- players -----------------------------------------------------------

    suspend fun getPlayers(): List<HeosPlayer> =
        connection.command("player", "get_players").payload?.toPlayers().orEmpty()

    suspend fun getPlayState(pid: String): PlayState? =
        PlayState.fromWire(connection.command("player", "get_play_state", listOf("pid" to pid)).attributes["state"])

    suspend fun setPlayState(pid: String, state: PlayState) =
        connection.command("player", "set_play_state", listOf("pid" to pid, "state" to state.wire))

    suspend fun playNext(pid: String) = connection.command("player", "play_next", listOf("pid" to pid))

    suspend fun playPrevious(pid: String) = connection.command("player", "play_previous", listOf("pid" to pid))

    suspend fun getNowPlaying(pid: String): NowPlaying? =
        connection.command("player", "get_now_playing_media", listOf("pid" to pid)).payload?.toNowPlaying()

    suspend fun getVolume(pid: String): Int? =
        connection.command("player", "get_volume", listOf("pid" to pid)).attributes["level"]?.toIntOrNull()

    suspend fun setVolume(pid: String, level: Int) =
        connection.command("player", "set_volume", listOf("pid" to pid, "level" to level.coerceIn(0, 100).toString()))

    suspend fun setMute(pid: String, mute: Boolean) =
        connection.command("player", "set_mute", listOf("pid" to pid, "state" to if (mute) "on" else "off"))

    suspend fun setPlayMode(pid: String, repeat: RepeatMode, shuffle: Boolean) =
        connection.command(
            "player",
            "set_play_mode",
            listOf("pid" to pid, "repeat" to repeat.wire, "shuffle" to if (shuffle) "on" else "off"),
        )

    // -- queue -------------------------------------------------------------

    suspend fun getQueue(pid: String, start: Int = 0, end: Int = start + DEFAULT_PAGE_SIZE - 1): List<QueueItem> =
        connection.command("player", "get_queue", listOf("pid" to pid, "range" to "$start,$end"))
            .payload?.toQueueItems().orEmpty()

    suspend fun playQueueItem(pid: String, qid: Int) =
        connection.command("player", "play_queue", listOf("pid" to pid, "qid" to qid.toString()))

    suspend fun removeFromQueue(pid: String, qids: List<Int>) =
        connection.command("player", "remove_from_queue", listOf("pid" to pid, "qid" to qids.joinToString(",")))

    suspend fun clearQueue(pid: String) = connection.command("player", "clear_queue", listOf("pid" to pid))

    suspend fun moveQueueItem(pid: String, sourceQids: List<Int>, destinationQid: Int) =
        connection.command(
            "player",
            "move_queue_item",
            listOf("pid" to pid, "sqid" to sourceQids.joinToString(","), "dqid" to destinationQid.toString()),
        )

    suspend fun saveQueueAsPlaylist(pid: String, name: String) =
        connection.command("player", "save_queue", listOf("pid" to pid, "name" to name))

    // -- browse ------------------------------------------------------------

    suspend fun getMusicSources(): List<MusicSource> =
        connection.command("browse", "get_music_sources").payload?.toMusicSources().orEmpty()

    /**
     * Fetches one page of a source or container.
     *
     * [cid] is omitted to list a source's top level. Pass identifiers exactly as they came back from
     * a previous browse: they are already escaped, and re-escaping them corrupts them.
     */
    suspend fun browse(sid: String, cid: String? = null, start: Int? = null, end: Int? = null): BrowsePage {
        val attributes = buildList {
            add("sid" to sid)
            cid?.let { add("cid" to it) }
            if (start != null && end != null) add("range" to "$start,$end")
        }
        val frame = connection.command("browse", "browse", attributes)
        val items = frame.payload?.toBrowseItems().orEmpty()
        return BrowsePage(
            items = items,
            count = frame.attributes["count"]?.toIntOrNull() ?: 0,
            returned = frame.attributes["returned"]?.toIntOrNull() ?: items.size,
            optionIds = frame.options.toOptionIds(),
        )
    }

    /**
     * Walks every page of a container.
     *
     * The spec allows `count` to be zero, meaning the receiver does not know how big the container
     * is; in that case the only termination signal is an empty page, so we cannot simply loop to
     * `count`. Guard against a server that keeps returning the same page forever with a hard cap.
     */
    fun browseAll(sid: String, cid: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): Flow<BrowsePage> = flow {
        var start = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = browse(sid, cid, start, start + pageSize - 1)
            if (page.items.isEmpty()) break
            emit(page)
            pages++
            start += page.items.size
            if (page.count > 0 && start >= page.count) break
            if (page.items.size < pageSize) break
        }
    }

    /**
     * Queues a container (every track inside it) or a single track when [mid] is given.
     *
     * This is the command that makes gapless playback possible: the receiver owns the resulting
     * queue and fetches the audio itself, so nothing on the phone sits in the audio path.
     */
    suspend fun addToQueue(
        pid: String,
        sid: String,
        cid: String,
        mid: String? = null,
        criteria: AddCriteria,
    ) = connection.command(
        "browse",
        "add_to_queue",
        buildList {
            add("pid" to pid)
            add("sid" to sid)
            add("cid" to cid)
            mid?.let { add("mid" to it) }
            add("aid" to criteria.aid.toString())
        },
    )

    /**
     * Plays a bare URL.
     *
     * Deliberately isolated: this is the degraded path. It starts exactly one stream, cannot queue,
     * gives no gapless transition and will not carry DSD. Reach for [addToQueue] wherever the
     * receiver knows about the media already.
     */
    suspend fun playStream(pid: String, url: String) =
        connection.command("browse", "play_stream", listOf("pid" to pid, HeosProtocol.URL to url))

    companion object {
        /** The spec caps a browse response at 50 or 100 rows depending on source type. */
        const val DEFAULT_PAGE_SIZE: Int = 50
        private const val MAX_PAGES: Int = 400
    }
}

enum class RepeatMode(val wire: String) {
    All("on_all"),
    One("on_one"),
    Off("off"),
    ;

    companion object {
        fun fromWire(value: String?): RepeatMode? = entries.firstOrNull { it.wire == value }
    }
}
