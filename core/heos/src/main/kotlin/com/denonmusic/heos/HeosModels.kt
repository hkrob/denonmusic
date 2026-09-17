package com.denonmusic.heos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A decoded frame from the receiver: either a command response or an unsolicited event. */
data class HeosFrame(
    /** e.g. `browse/browse`, or `event/player_state_changed`. */
    val command: String,
    /** Absent on events, which carry no result field. */
    val result: String?,
    val attributes: Map<String, String>,
    val payload: JsonElement?,
    val options: JsonElement?,
) {
    val isEvent: Boolean get() = command.startsWith("event/")
    val isSuccess: Boolean get() = result == "success"

    /** Correlation id echoed back from the command, when one was sent. */
    val sequence: Long? get() = attributes[HeosProtocol.SEQUENCE]?.toLongOrNull()

    /**
     * True for the interim ack a slow command (e.g. browsing into a DLNA/Plex source, which the
     * receiver has to poll out-of-band) sends before its real result. This ack echoes back every
     * argument the caller sent - [sequence] included - so it is otherwise indistinguishable from the
     * final response by correlation id alone; callers must let this one pass through and keep
     * waiting for the frame that actually carries a result.
     */
    val isCommandUnderProcess: Boolean get() = attributes.containsKey("command under process")

    /** Event name with the `event/` prefix stripped, or null for responses. */
    val eventName: String? get() = if (isEvent) command.removePrefix("event/") else null

    val payloadArray: JsonArray? get() = (payload as? JsonArray)

    /**
     * Error detail for a failed command. The spec returns `eid` (error id) and `text` in the
     * message field of a `fail` result.
     */
    fun asError(): HeosError? =
        if (result == "fail" || result == "error") {
            HeosError(
                command = command,
                errorId = attributes["eid"]?.toIntOrNull() ?: -1,
                text = attributes["text"].orEmpty(),
                systemErrorNumber = attributes["syserrno"]?.toIntOrNull(),
            )
        } else {
            null
        }
}

data class HeosError(
    val command: String,
    val errorId: Int,
    val text: String,
    val systemErrorNumber: Int? = null,
) {
    /** True when the container or media id we asked for no longer exists on the server. */
    val isMissingEntity: Boolean
        get() = errorId == ERROR_INVALID_ID || text.contains("not found", ignoreCase = true)

    companion object {
        /** "Invalid ID" per the spec's error code table. */
        const val ERROR_INVALID_ID: Int = 8
    }
}

class HeosCommandException(val error: HeosError) :
    RuntimeException("HEOS ${error.command} failed: eid=${error.errorId} ${error.text}")

/** A top-level music source from `browse/get_music_sources`. */
data class MusicSource(
    val sid: String,
    val name: String,
    val type: String,
    val imageUrl: String?,
    val available: Boolean,
) {
    /**
     * True for the source that aggregates USB media, DLNA servers and HEOS-configured SMB network
     * shares. This is the one that supports real queueing, and therefore gapless playback and DSD.
     */
    val isLocalMedia: Boolean
        get() = sid == SID_LOCAL_MEDIA || type == TYPE_DLNA_SERVER || type == TYPE_HEOS_SERVER

    companion object {
        /** "Local USB Media / Local DLNA servers" per the spec's source id table. */
        const val SID_LOCAL_MEDIA: String = "1024"
        const val TYPE_DLNA_SERVER: String = "dlna_server"
        const val TYPE_HEOS_SERVER: String = "heos_server"
    }
}

/** One row from `browse/browse`: either a navigable container or a playable track. */
data class BrowseItem(
    val name: String,
    val imageUrl: String?,
    val mediaType: String?,
    val cid: String?,
    val mid: String?,
    val isContainer: Boolean,
    val isPlayable: Boolean,
    val artist: String?,
    val album: String?,
    /**
     * Set instead of [cid] for a nested music source - e.g. browsing the aggregate "Local Music"
     * source (sid 1024) returns one row per DLNA/HEOS server behind it, each carrying its own `sid`
     * rather than a `cid` within 1024. Navigating into one of these means switching to *this* sid
     * with a null cid, not appending a cid onto the parent's sid.
     */
    val sid: String?,
) {
    /** A track can be queued directly with its [mid]. */
    val isTrack: Boolean get() = !isContainer && isPlayable && mid != null
}

/**
 * How an item should enter the queue. The values are the protocol's own `aid` numbers, so the
 * user-facing menu maps one-to-one onto the wire format.
 */
enum class AddCriteria(val aid: Int) {
    PlayNow(1),
    PlayNext(2),
    AddToEnd(3),
    ReplaceAndPlay(4),
}

/**
 * Browse-level options returned alongside a container listing. Only [PlayableContainer] is load
 * bearing for us: it is what licenses a "play all" action on a folder.
 */
object BrowseOption {
    const val ADD_TO_HEOS_FAVORITES: Int = 19
    const val PLAYABLE_CONTAINER: Int = 21
}

/** A page of a container listing, plus enough metadata to drive paging. */
data class BrowsePage(
    val items: List<BrowseItem>,
    /** Total items in the container. Zero means the server does not know; page until empty. */
    val count: Int,
    val returned: Int,
    val optionIds: Set<Int>,
) {
    val isPlayableContainer: Boolean get() = BrowseOption.PLAYABLE_CONTAINER in optionIds
}

data class QueueItem(
    val qid: Int,
    val mid: String,
    val song: String,
    val album: String,
    val artist: String,
    val imageUrl: String?,
)

enum class PlayState(val wire: String) {
    Play("play"),
    Pause("pause"),
    Stop("stop"),
    ;

    companion object {
        fun fromWire(value: String?): PlayState? = entries.firstOrNull { it.wire == value }
    }
}

data class NowPlaying(
    val type: String?,
    val song: String,
    val album: String,
    val artist: String,
    val imageUrl: String?,
    val mid: String?,
    val qid: Int?,
    val sid: String?,
)

data class HeosPlayer(
    val pid: String,
    val name: String,
    val model: String?,
    val version: String?,
    val serial: String?,
)

// ---------------------------------------------------------------------------
// Payload mapping
// ---------------------------------------------------------------------------

internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }

/**
 * Same as [str], but for fields that are free-text bound for display (`name`, `song`, `artist`,
 * `album`) rather than identifiers (`cid`, `mid`, `sid`) - the wire escapes `&`/`=`/`%` in these per
 * [HeosProtocol.escape], so a DLNA/Plex-sourced title containing `&` would otherwise show up to the
 * user as the literal text `%26`. Never apply this to an identifier: those must be handed back to
 * the receiver exactly as received, still escaped.
 *
 * Also XML/HTML-unescaped: user-reported a real DLNA-indexed track showing "Girls &amp; Boys"
 * verbatim instead of "Girls & Boys" - the receiver's own HEOS firmware forwards a DLNA source's
 * `<dc:title>` text straight out of its DIDL-Lite XML without decoding the entities XML requires
 * there in the first place. A completely separate leak from the wire's own `%26` escaping above;
 * both can appear in the same field depending on the source.
 */
internal fun JsonObject.displayStr(key: String): String? = str(key)?.let(HeosProtocol::unescape)?.let(::xmlUnescape)

private val NUMERIC_XML_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

/** The five predefined XML entities plus decimal/hex numeric character references. */
internal fun xmlUnescape(value: String): String {
    if ('&' !in value) return value
    val named = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
    if ('#' !in named) return named
    return NUMERIC_XML_ENTITY.replace(named) { match ->
        val body = match.groupValues[1]
        val codePoint = if (body.startsWith("x", ignoreCase = true)) {
            body.substring(1).toIntOrNull(16)
        } else {
            body.toIntOrNull()
        }
        codePoint?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
    }
}

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

internal fun JsonObject.bool(key: String): Boolean? = when (str(key)?.lowercase()) {
    "yes", "true" -> true
    "no", "false" -> false
    else -> null
}

internal fun JsonElement.toMusicSources(): List<MusicSource> =
    (this as? JsonArray).orEmptyArray().map { element ->
        val obj = element.jsonObject
        MusicSource(
            sid = obj.str("sid").orEmpty(),
            name = obj.displayStr("name").orEmpty(),
            type = obj.str("type").orEmpty(),
            imageUrl = obj.str("image_url"),
            available = obj.bool("available") ?: true,
        )
    }

internal fun JsonElement.toBrowseItems(): List<BrowseItem> =
    (this as? JsonArray).orEmptyArray().map { element ->
        val obj = element.jsonObject
        val sid = obj.str("sid")
        BrowseItem(
            name = obj.displayStr("name").orEmpty(),
            imageUrl = obj.str("image_url"),
            mediaType = obj.str("type"),
            cid = obj.str("cid"),
            mid = obj.str("mid"),
            // A nested-source row (browsing the aggregate "Local Music" source down into one DLNA/HEOS
            // server behind it) carries a bare `sid` with no `container` field at all - still a
            // navigable row, so a missing `container` defaults to true whenever there's a sid to
            // browse into rather than defaulting to a dead end.
            isContainer = obj.bool("container") ?: (sid != null),
            isPlayable = obj.bool("playable") ?: false,
            artist = obj.displayStr("artist"),
            album = obj.displayStr("album"),
            sid = sid,
        )
    }

internal fun JsonElement.toQueueItems(): List<QueueItem> =
    (this as? JsonArray).orEmptyArray().mapNotNull { element ->
        val obj = element.jsonObject
        val qid = obj.int("qid") ?: return@mapNotNull null
        QueueItem(
            qid = qid,
            mid = obj.str("mid").orEmpty(),
            song = obj.displayStr("song").orEmpty(),
            album = obj.displayStr("album").orEmpty(),
            artist = obj.displayStr("artist").orEmpty(),
            imageUrl = obj.str("image_url"),
        )
    }

internal fun JsonElement.toPlayers(): List<HeosPlayer> =
    (this as? JsonArray).orEmptyArray().mapNotNull { element ->
        val obj = element.jsonObject
        val pid = obj.str("pid") ?: return@mapNotNull null
        HeosPlayer(
            pid = pid,
            name = obj.displayStr("name").orEmpty(),
            model = obj.str("model"),
            version = obj.str("version"),
            serial = obj.str("serial"),
        )
    }

internal fun JsonElement.toNowPlaying(): NowPlaying? {
    // get_now_playing_media returns a single-element array rather than a bare object.
    val obj = (this as? JsonArray)?.firstOrNull()?.jsonObject ?: (this as? JsonObject) ?: return null
    return NowPlaying(
        type = obj.str("type"),
        song = obj.displayStr("song").orEmpty(),
        album = obj.displayStr("album").orEmpty(),
        artist = obj.displayStr("artist").orEmpty(),
        imageUrl = obj.str("image_url"),
        mid = obj.str("mid"),
        qid = obj.int("qid"),
        sid = obj.str("sid"),
    )
}

/**
 * Collects the numeric option ids from a browse response's `options` block, whose shape is
 * `[{"browse": [{"id": 21, "name": "Play All"}]}]`.
 */
internal fun JsonElement?.toOptionIds(): Set<Int> {
    val array = (this as? JsonArray) ?: return emptySet()
    return array.flatMap { entry ->
        val browse = (entry as? JsonObject)?.get("browse") as? JsonArray ?: JsonArray(emptyList())
        browse.mapNotNull { (it as? JsonObject)?.int("id") }
    }.toSet()
}

private fun JsonArray?.orEmptyArray(): List<JsonElement> = this ?: emptyList()

internal fun JsonElement.asArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
internal fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
