package com.denonmusic.app.lancontrol

import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.avr.SoundMode
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.PlayState
import com.denonmusic.heos.QueueItem
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var server: LanControlServer? = null
    private var cachedPid: String? = null

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
        val client = heosSession.heosClient
            ?: return@runBlocking LanControlResponse(503, """{"error":"HEOS not connected"}""")
        val pid = resolvePid(client) ?: return@runBlocking LanControlResponse(503, """{"error":"no player found"}""")

        when (request.method to request.path) {
            "GET" to "/status" -> statusResponse(client, pid)
            "GET" to "/queue" -> queueResponse(client, pid)
            "POST" to "/play" -> ok { client.setPlayState(pid, PlayState.Play) }
            "POST" to "/pause" -> ok { client.setPlayState(pid, PlayState.Pause) }
            "POST" to "/stop" -> ok { client.setPlayState(pid, PlayState.Stop) }
            "POST" to "/next" -> ok { client.playNext(pid) }
            "POST" to "/previous" -> ok { client.playPrevious(pid) }
            "POST" to "/volume" -> withIntParam(request, "level") { level -> client.setVolume(pid, level) }
            "POST" to "/mute" -> withBoolParam(request, "on") { on -> client.setMute(pid, on) }
            "POST" to "/queue/play" -> withIntParam(request, "qid") { qid -> client.playQueueItem(pid, qid) }
            "POST" to "/power" -> handlePower(request)
            "POST" to "/soundmode" -> handleSoundMode(request)
            else -> LanControlResponse.notFound()
        }
    }

    private suspend fun resolvePid(client: HeosClient): String? {
        cachedPid?.let { return it }
        val pid = runCatching { client.getPlayers() }.getOrNull()?.firstOrNull()?.pid
        cachedPid = pid
        return pid
    }

    private suspend fun statusResponse(client: HeosClient, pid: String): LanControlResponse {
        val nowPlaying = runCatching { client.getNowPlaying(pid) }.getOrNull()
        val playState = runCatching { client.getPlayState(pid) }.getOrNull()
        val volume = runCatching { client.getVolume(pid) }.getOrNull()
        val playMode = runCatching { client.getPlayMode(pid) }.getOrNull()
        val avrClient = avrSession.avrClient
        val power = avrClient?.let { runCatching { it.powerState() }.getOrNull() }
        val soundMode = avrClient?.let { runCatching { it.soundMode() }.getOrNull() }

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
            append(""""soundMode":${jsonString(soundMode?.name)}""")
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
