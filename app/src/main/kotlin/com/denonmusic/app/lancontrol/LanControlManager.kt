package com.denonmusic.app.lancontrol

import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.bridge.BridgeQueueItem
import com.denonmusic.app.browse.SourceRepository
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.media.MediaInfoRepository
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.avr.SoundMode
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueItem
import com.denonmusic.heos.QueueTargetResolver
import com.denonmusic.smb.SmbCredentials
import com.denonmusic.smb.SmbEntry
import com.denonmusic.smb.SmbOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LanControlStatus {
    data object Off : LanControlStatus
    data class Running(val url: String) : LanControlStatus
    data class Error(val reason: String) : LanControlStatus
}

/**
 * Owns the LAN control HTTP API end to end: watches settings for `lanControlEnabled` +
 * `lanControlPassword`, starts/stops [LanControlServer] to match, and dispatches its requests onto
 * [HeosSession]/[AvrSession] - the same session singletons the UI itself drives, so a command from a
 * LAN client and a tap in the app land on the identical connection and queue state.
 *
 * A plain singleton rather than a foreground service (matching [com.denonmusic.app.bridge.SmbBridgeService]'s
 * existing scope): it runs for as long as the app process is alive, same as every other live
 * connection this app holds. Surviving the app being fully backgrounded/killed would need a real
 * foreground service, which is out of scope here - see the plan's own note on the bridge fallback.
 */
@Singleton
class LanControlManager @Inject constructor(
    private val heosSession: HeosSession,
    private val avrSession: AvrSession,
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val mediaInfoRepository: MediaInfoRepository,
    private val bridgeQueueController: BridgeQueueController,
    private val playbackStarter: HeosPlaybackStarter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var server: LanControlServer? = null

    // Progress for whatever folder-wide play/queue walk (SMB or HEOS) is currently running, if any -
    // polled by the web UI via GET /progress so a big folder's tens-of-seconds walk shows live
    // feedback instead of looking stuck. @Volatile: written from whichever client thread is running
    // the walk, read from whichever client thread is polling - see LanControlServer's thread-per-
    // connection model.
    @Volatile private var progressActive = false

    @Volatile private var progressCount = 0

    @Volatile private var progressMessage = ""

    private val _status = MutableStateFlow<LanControlStatus>(LanControlStatus.Off)
    val status: StateFlow<LanControlStatus> = _status.asStateFlow()

    /** Idempotent - safe to call from every place that might be the first thing running this launch. */
    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            settings.settings.collectLatest { current ->
                current.avrHost?.let { host ->
                    heosSession.start(host)
                    avrSession.start(host)
                }
                val password = current.lanControlPassword
                if (current.lanControlEnabled && !password.isNullOrBlank()) {
                    startServer(password)
                } else {
                    stopServer()
                    _status.value = LanControlStatus.Off
                }
            }
        }
    }

    private fun startServer(password: String) {
        val existing = server
        if (existing != null && existing.isRunning) return
        existing?.close()

        val newServer = LanControlServer(authToken = { currentPassword() }, handle = ::handle)
        val bound = newServer.start(LAN_CONTROL_PORT)
        if (!bound) {
            _status.value = LanControlStatus.Error("Port $LAN_CONTROL_PORT already in use")
            return
        }
        server = newServer
        val ip = localLanAddress()
        _status.value = if (ip != null) {
            LanControlStatus.Running("http://$ip:${newServer.port}")
        } else {
            LanControlStatus.Error("No LAN address found")
        }
    }

    private fun stopServer() {
        server?.close()
        server = null
    }

    private fun currentPassword(): String? = runBlocking { settings.settings.first().lanControlPassword }

    private fun handle(request: LanControlRequest): LanControlResponse = runBlocking {
        when (request.method to request.path) {
            // The SMB fallback browser talks straight to the share, independent of HEOS/AVR -
            // dispatched before the HEOS gate below so it still works while the receiver is off.
            "GET" to "/smb" -> smbListResponse(request)
            "POST" to "/smb/play" -> smbPlayResponse(request)
            "POST" to "/smb/queue" -> smbQueueResponse(request)
            "GET" to "/progress" -> progressResponse()
            else -> heosHandle(request)
        }
    }

    private suspend fun heosHandle(request: LanControlRequest): LanControlResponse {
        val client = heosSession.heosClient
            ?: return LanControlResponse(503, """{"error":"HEOS not connected"}""")
        // Re-resolved per request via the session rather than cached here: see HeosSession.resolvePid
        // for why a latched pid wedges this whole surface until the process is killed.
        val pid = heosSession.resolvePid()
            ?: return LanControlResponse(503, """{"error":"no player found"}""")

        return when (request.method to request.path) {
            "GET" to "/status" -> statusResponse(client, pid)
            "GET" to "/queue" -> queueResponse(client, pid)
            "POST" to "/play" -> ok { client.setPlayState(pid, PlayState.Play) }
            "POST" to "/pause" -> ok { client.setPlayState(pid, PlayState.Pause) }
            "POST" to "/stop" -> ok { client.setPlayState(pid, PlayState.Stop) }
            // A play_stream track has no real HEOS queue behind it, so - same as PlayerViewModel.playNext/
            // playPrevious - skip has to step the bridge's own client-side queue instead while it's what's
            // actually driving the receiver; the real player/play_next would silently act on whatever real
            // queue was last loaded, not the bridge track the user (and the phone app) currently sees.
            "POST" to "/next" -> ok {
                if (bridgeQueueController.state.value.currentItem != null) bridgeQueueController.next() else client.playNext(pid)
            }
            "POST" to "/previous" -> ok {
                if (bridgeQueueController.state.value.currentItem != null) bridgeQueueController.previous() else client.playPrevious(pid)
            }
            "POST" to "/volume" -> withIntParam(request, "level") { level -> client.setVolume(pid, level) }
            "POST" to "/mute" -> withBoolParam(request, "on") { on -> client.setMute(pid, on) }
            // Via playbackStarter, which relinquishes the bridge queue's ownership of Now
            // Playing/transport - otherwise the phone app's own UI would keep showing the finished
            // bridge track after a LAN-control client started real playback.
            "POST" to "/queue/play" -> withIntParam(request, "qid") { qid -> playbackStarter.playQueueItem(client, pid, qid) }
            "POST" to "/power" -> handlePower(request)
            "POST" to "/soundmode" -> handleSoundMode(request)
            "POST" to "/input" -> handleInput(request)
            "POST" to "/bitperfect" -> handleBitPerfect(request)
            "GET" to "/browse" -> browseResponse(client, request)
            "POST" to "/browse/queue" -> browseQueueResponse(client, pid, request)
            "POST" to "/browse/playall" -> browsePlayAllResponse(client, pid, request)
            else -> LanControlResponse.notFound()
        }
    }

    private suspend fun statusResponse(client: HeosClient, pid: String): LanControlResponse {
        val nowPlaying = runCatching { client.getNowPlaying(pid) }.getOrNull()
        val playState = runCatching { client.getPlayState(pid) }.getOrNull()
        val volume = runCatching { client.getVolume(pid) }.getOrNull()
        val playMode = runCatching { client.getPlayMode(pid) }.getOrNull()
        val avrClient = avrSession.avrClient
        val power = avrClient?.let { runCatching { it.powerState() }.getOrNull() }
        val soundMode = avrClient?.let { runCatching { it.soundMode() }.getOrNull() }
        val inputSource = avrClient?.let { runCatching { it.inputSource() }.getOrNull() }
        val signalType = avrClient?.let { runCatching { it.signalType() }.getOrNull() }
        val sampleRateKhz = avrClient?.let { runCatching { it.sampleRateKhz() }.getOrNull() }
        val outputChannels = avrClient?.let { runCatching { it.outputChannels() }.getOrNull() }.orEmpty()
        val bitPerfectPolicy = runCatching { settings.settings.first().bitPerfectPolicy }.getOrNull()

        val json = buildString {
            append("{")
            append(""""song":${jsonString(nowPlaying?.song)},""")
            append(""""artist":${jsonString(nowPlaying?.artist)},""")
            append(""""album":${jsonString(nowPlaying?.album)},""")
            append(""""imageUrl":${jsonString(nowPlaying?.imageUrl)},""")
            append(""""playState":${jsonString(playState?.wire)},""")
            append(""""volume":${volume ?: "null"},""")
            append(""""repeat":${jsonString(playMode?.repeat?.wire)},""")
            append(""""shuffle":${playMode?.shuffle ?: "null"},""")
            append(""""avrPower":${jsonString(power?.name)},""")
            append(""""soundMode":${jsonString(soundMode?.name)},""")
            append(""""input":${jsonString(inputSource)},""")
            append(""""signalType":${jsonString(signalType?.name)},""")
            append(""""sampleRateKhz":${sampleRateKhz ?: "null"},""")
            val channelsJson = outputChannels.joinToString(prefix = "[", postfix = "]") {
                """{"code":${jsonString(it.code)},"level":${jsonString(it.level)}}"""
            }
            append(""""outputChannels":$channelsJson,""")
            append(""""bitPerfectPolicy":${jsonString(bitPerfectPolicy)}""")
            append("}")
        }
        return LanControlResponse.ok(json)
    }

    private suspend fun queueResponse(client: HeosClient, pid: String): LanControlResponse {
        val items = runCatching { client.getQueue(pid) }.getOrDefault(emptyList())
        val json = items.joinToString(prefix = "[", postfix = "]") { it.toJson() }
        return LanControlResponse.ok(json)
    }

    private fun QueueItem.toJson(): String = buildString {
        append("{")
        append(""""qid":$qid,""")
        append(""""song":${jsonString(song)},""")
        append(""""artist":${jsonString(artist)},""")
        append(""""album":${jsonString(album)}""")
        append("}")
    }

    private suspend fun handlePower(request: LanControlRequest): LanControlResponse {
        val client = avrSession.avrClient ?: return LanControlResponse(503, """{"error":"AVR not connected"}""")
        val on = request.query["on"]?.let(::parseBool)
            ?: return LanControlResponse(400, """{"error":"missing or invalid 'on' (true/false)"}""")
        return ok { if (on) client.powerOn() else client.powerStandby() }
    }

    private suspend fun handleSoundMode(request: LanControlRequest): LanControlResponse {
        val client = avrSession.avrClient ?: return LanControlResponse(503, """{"error":"AVR not connected"}""")
        val name = request.query["mode"]
        val mode = SoundMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return LanControlResponse(400, """{"error":"unknown mode, expected one of ${SoundMode.entries.joinToString { it.name }}"}""")
        return ok { client.setSoundMode(mode) }
    }

    private suspend fun handleInput(request: LanControlRequest): LanControlResponse {
        val client = avrSession.avrClient ?: return LanControlResponse(503, """{"error":"AVR not connected"}""")
        val mnemonic = request.query["mnemonic"]?.takeIf { it.isNotBlank() }
            ?: return LanControlResponse(400, """{"error":"missing 'mnemonic'"}""")
        return ok { client.selectInput(mnemonic) }
    }

    /** Same persist-then-apply-live behaviour as [com.denonmusic.app.avr.AvrViewModel.setBitPerfectPolicy]. */
    private suspend fun handleBitPerfect(request: LanControlRequest): LanControlResponse {
        val name = request.query["policy"]
        val policy = BitPerfectPolicy.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (policy == null) {
            val known = BitPerfectPolicy.entries.joinToString { it.name }
            return LanControlResponse(400, """{"error":"unknown policy, expected one of $known"}""")
        }
        settings.setBitPerfectPolicy(policy.name)
        val client = avrSession.avrClient
        return ok { client?.let { it.applyBitPerfectPolicy(policy) } }
    }

    /**
     * Lists one level of the HEOS-indexed library: `sid` defaults to the auto-detected local source
     * (same as [com.denonmusic.app.browse.BrowseViewModel]'s bootstrap), `cid` defaults to that
     * source's root. The caller (the web UI) keeps its own breadcrumb - each returned item already
     * carries the `sid`/`cid` needed to browse into it, so this endpoint itself is stateless.
     */
    private suspend fun browseResponse(client: HeosClient, request: LanControlRequest): LanControlResponse {
        val sid = request.query["sid"] ?: sourceRepository.resolveCachedSid(client)
            ?: return LanControlResponse(503, """{"error":"no queueable music source found"}""")
        val cid = request.query["cid"]
        val page = runCatching { client.browse(sid, cid) }.getOrElse { e ->
            return LanControlResponse(500, """{"error":${jsonString(e.message ?: "browse failed")}}""")
        }
        val json = buildString {
            append("{")
            append(""""sid":${jsonString(sid)},""")
            append(""""cid":${jsonString(cid)},""")
            append(""""isPlayableContainer":${page.isPlayableContainer},""")
            append(""""items":${page.items.joinToString(prefix = "[", postfix = "]") { it.toJson() }}""")
            append("}")
        }
        return LanControlResponse.ok(json)
    }

    private fun BrowseItem.toJson(): String = buildString {
        append("{")
        append(""""name":${jsonString(name)},""")
        append(""""sid":${jsonString(sid)},""")
        append(""""cid":${jsonString(cid)},""")
        append(""""mid":${jsonString(mid)},""")
        append(""""isContainer":$isContainer,""")
        append(""""isTrack":$isTrack,""")
        append(""""artist":${jsonString(artist)},""")
        append(""""album":${jsonString(album)},""")
        append(""""imageUrl":${jsonString(imageUrl)}""")
        append("}")
    }

    private suspend fun browseQueueResponse(client: HeosClient, pid: String, request: LanControlRequest): LanControlResponse {
        val sid = request.query["sid"] ?: return LanControlResponse(400, """{"error":"missing 'sid'"}""")
        val cid = request.query["cid"] ?: return LanControlResponse(400, """{"error":"missing 'cid'"}""")
        val mid = request.query["mid"]
        val criteria = parseAddCriteria(request) ?: return badCriteria()
        return ok { playbackStarter.addToQueue(client, pid, sid, cid, mid, criteria) }
    }

    /**
     * Walks the whole subtree under `sid`/`cid`, same as
     * [com.denonmusic.app.browse.BrowseViewModel.playAllCurrentContainer] - a multi-disc album
     * (`CD1`/`CD2` subfolders, no tracks at the album's own level) never gets HEOS's own
     * "playable container" flag, so a plain `addToQueue(cid)` would silently do nothing for it.
     */
    private suspend fun browsePlayAllResponse(client: HeosClient, pid: String, request: LanControlRequest): LanControlResponse {
        val sid = request.query["sid"] ?: return LanControlResponse(400, """{"error":"missing 'sid'"}""")
        val cid = request.query["cid"]
        val criteria = parseAddCriteria(request) ?: return badCriteria()

        beginProgress("Scanning library…")
        val targets = try {
            runCatching {
                QueueTargetResolver.collect(client, sid, cid) { gathered ->
                    bumpProgress("Scanning library… ($gathered found)")
                }
            }.getOrElse { e ->
                return LanControlResponse(500, """{"error":${jsonString(e.message ?: "couldn't gather tracks")}}""")
            }
        } finally {
            endProgress()
        }
        if (targets.isEmpty()) return LanControlResponse(404, """{"error":"nothing playable found"}""")
        if (targets.size > QueueTargetResolver.DEFAULT_MAX_QUEUE_TARGETS) {
            return LanControlResponse(
                400,
                """{"error":"too many items to queue at once """ +
                    """(limit ${QueueTargetResolver.DEFAULT_MAX_QUEUE_TARGETS}) - open a smaller folder"}""",
            )
        }
        beginProgress("Queuing 0/${targets.size}…")
        try {
            playbackStarter.addAll(client, pid, targets, criteria) { done ->
                bumpProgress("Queuing $done/${targets.size}…")
            }
        } finally {
            endProgress()
        }
        return LanControlResponse.ok("""{"ok":true,"queued":${targets.size}}""")
    }

    private fun parseAddCriteria(request: LanControlRequest): AddCriteria? =
        request.query["criteria"]?.let { name -> AddCriteria.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    private fun badCriteria(): LanControlResponse {
        val known = AddCriteria.entries.joinToString { it.name }
        return LanControlResponse(400, """{"error":"missing or invalid 'criteria', expected one of $known"}""")
    }

    /** `null` when no share is configured yet - same check [com.denonmusic.app.bridge.SmbBrowseViewModel] makes. */
    private suspend fun smbOverlay(): SmbOverlay? {
        val saved = settings.settings.first()
        val host = saved.smbHost?.takeIf { it.isNotBlank() } ?: return null
        val share = saved.smbShare?.takeIf { it.isNotBlank() } ?: return null
        val credentials = SmbCredentials(host, share, saved.smbUsername.orEmpty(), saved.smbPassword.orEmpty())
        return mediaInfoRepository.overlayFor(credentials)
    }

    private suspend fun smbListResponse(request: LanControlRequest): LanControlResponse {
        val overlay = smbOverlay() ?: return LanControlResponse(503, """{"error":"no SMB share configured"}""")
        val path = request.query["path"].orEmpty()
        val entries = withContext(Dispatchers.IO) { runCatching { overlay.listDirectory(path) }.getOrNull() }
            ?: return LanControlResponse(404, """{"error":"couldn't list this folder"}""")
        val json = entries.joinToString(prefix = "[", postfix = "]") { it.toJson() }
        return LanControlResponse.ok(json)
    }

    private fun SmbEntry.toJson(): String = buildString {
        append("{")
        append(""""name":${jsonString(name)},""")
        append(""""path":${jsonString(path)},""")
        append(""""isDirectory":$isDirectory""")
        append("}")
    }

    /**
     * Plays every file under `path` (recursively, same as tapping a folder in the app's own SMB
     * browser) - `file`, if given, must be one of those paths and moves the start point to it, the
     * same "tap a track, play the rest of its folder after" behaviour as
     * [com.denonmusic.app.browse.BrowseViewModel.playBridgeFolder].
     */
    private suspend fun smbPlayResponse(request: LanControlRequest): LanControlResponse {
        val overlay = smbOverlay() ?: return LanControlResponse(503, """{"error":"no SMB share configured"}""")
        val dir = request.query["path"].orEmpty()
        val files = scanSmbFolder(overlay, dir)
        if (files.isEmpty()) return LanControlResponse(404, """{"error":"no playable files found"}""")
        val startFile = request.query["file"]
        val startIndex = startFile?.let { f -> files.indexOfFirst { it.path == f }.takeIf { it >= 0 } } ?: 0
        bridgeQueueController.replaceQueueAndPlay(files.map { it.path.toBridgeQueueItem() }, startIndex)
        return LanControlResponse.ok("""{"ok":true,"queued":${files.size}}""")
    }

    /** Appends without disturbing whatever's already playing - the browser's "add to queue" action. */
    private suspend fun smbQueueResponse(request: LanControlRequest): LanControlResponse {
        val overlay = smbOverlay() ?: return LanControlResponse(503, """{"error":"no SMB share configured"}""")
        val dir = request.query["path"].orEmpty()
        val files = scanSmbFolder(overlay, dir)
        if (files.isEmpty()) return LanControlResponse(404, """{"error":"no playable files found"}""")
        bridgeQueueController.addToQueue(files.map { it.path.toBridgeQueueItem() })
        return LanControlResponse.ok("""{"ok":true,"queued":${files.size}}""")
    }

    /** Recursive SMB scan with live progress - see [progressResponse]; a big artist folder can take tens of seconds. */
    private suspend fun scanSmbFolder(overlay: SmbOverlay, dir: String): List<SmbEntry> {
        beginProgress("Scanning $dir…")
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    overlay.listFilesRecursive(dir) { scannedPath -> bumpProgress("Scanning $scannedPath…") }
                }.getOrDefault(emptyList())
            }
        } finally {
            endProgress()
        }
    }

    private fun String.toBridgeQueueItem() = BridgeQueueItem(path = this, displayName = substringAfterLast('/'))

    private fun beginProgress(message: String) {
        progressCount = 0
        progressMessage = message
        progressActive = true
    }

    private fun bumpProgress(message: String) {
        progressCount++
        progressMessage = message
    }

    private fun endProgress() {
        progressActive = false
    }

    private fun progressResponse(): LanControlResponse =
        LanControlResponse.ok("""{"active":$progressActive,"count":$progressCount,"message":${jsonString(progressMessage)}}""")

    private suspend fun withIntParam(request: LanControlRequest, name: String, action: suspend (Int) -> Unit): LanControlResponse {
        val value = request.query[name]?.toIntOrNull()
            ?: return LanControlResponse(400, """{"error":"missing or invalid '$name'"}""")
        return ok { action(value) }
    }

    private suspend fun withBoolParam(request: LanControlRequest, name: String, action: suspend (Boolean) -> Unit): LanControlResponse {
        val value = request.query[name]?.let(::parseBool)
            ?: return LanControlResponse(400, """{"error":"missing or invalid '$name' (true/false)"}""")
        return ok { action(value) }
    }

    private suspend fun ok(action: suspend () -> Unit): LanControlResponse {
        action()
        return LanControlResponse.ok("""{"ok":true}""")
    }

    /** First non-loopback IPv4 address on an "up" interface - the address a LAN client reaches us at. */
    private fun localLanAddress(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress

    companion object {
        /** Fixed and unlikely to collide with anything else this app or a common LAN service uses. */
        const val LAN_CONTROL_PORT: Int = 8901
    }
}

/** Accepts the same truthy/falsy vocabulary the rest of this app's wire protocols already use. */
private fun parseBool(value: String): Boolean? = when (value.lowercase()) {
    "true", "on", "1" -> true
    "false", "off", "0" -> false
    else -> null
}
