package com.denonmusic.app.heos

import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.HeosConnection
import com.denonmusic.heos.HeosFrame
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface HeosConnectionState {
    data object Disconnected : HeosConnectionState
    data object Connecting : HeosConnectionState
    data class Connected(val host: String) : HeosConnectionState
    data class Failed(val host: String, val reason: String) : HeosConnectionState
}

/**
 * Owns the two sockets a HEOS session needs (spec section on concurrent connections): one
 * registered for change events, one for request/response commands. Keeping them apart means a burst
 * of `player_now_playing_progress` events can never delay a queue command's reply.
 *
 * Reconnects both with exponential backoff and jitter, and heartbeats the command socket every 25s
 * per the spec's keepalive guidance.
 */
@Singleton
class HeosSession @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var eventConnection: HeosConnection? = null
    private var commandConnection: HeosConnection? = null
    private var client: HeosClient? = null
    private var sessionJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow<HeosConnectionState>(HeosConnectionState.Disconnected)
    val state: StateFlow<HeosConnectionState> = _state.asStateFlow()

    /** Null until a command connection exists; commands throw naturally if used before that. */
    val heosClient: HeosClient? get() = client

    val events: SharedFlow<HeosFrame>? get() = eventConnection?.events

    @Volatile
    private var _lastKnownPid: String? = null

    /**
     * The pid the last [resolvePid] saw, for callers that need "do we have a player at all" without
     * spending a round trip - a UI gate, say. Never use it to address a command: see [resolvePid].
     */
    val lastKnownPid: String? get() = _lastKnownPid

    /**
     * Asks the receiver which player to address, every time, and deliberately does not fall back to
     * [lastKnownPid] on failure.
     *
     * Caching this once is a trap the app has already fallen into twice. Confirmed live against a
     * real AVR-X4500H: its `player/get_players` can start answering with an empty list - independent
     * of any one client's session, and while the receiver's own audio, the official HEOS app, and
     * even this app's own `get_music_sources`/`heart_beat` keep working - typically staying that way
     * until the unit is power-cycled. A pid cached at first bootstrap and never re-checked leaves
     * every command aimed at a pid that no longer answers to anything, with no error to show for it.
     *
     * One `get_players` round trip is cheap next to the command that follows it, and returning null
     * when the registry is empty lets each caller surface the gap rather than silently no-op. This
     * lives on the session, the one object every control surface already injects, so there is nowhere
     * left for a per-surface cache to grow back.
     */
    suspend fun resolvePid(): String? {
        val client = client ?: return null
        val pid = runCatching { client.getPlayers() }.getOrNull()?.firstOrNull()?.pid
        _lastKnownPid = pid
        return pid
    }

    fun start(host: String) {
        if (_state.value.let { it is HeosConnectionState.Connected && it.host == host }) return
        sessionJob?.cancel()
        sessionJob = scope.launch { runSession(host) }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        eventConnection?.close()
        commandConnection?.close()
        client = null
        _lastKnownPid = null
        _state.value = HeosConnectionState.Disconnected
    }

    private suspend fun runSession(host: String) {
        var attempt = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            _state.value = HeosConnectionState.Connecting
            try {
                val events = HeosConnection(host)
                val commands = HeosConnection(host)
                events.connect()
                commands.connect()
                events.command("system", "register_for_change_events", listOf("enable" to "on"))

                eventConnection = events
                commandConnection = commands
                client = HeosClient(commands)
                attempt = 0
                _state.value = HeosConnectionState.Connected(host)

                heartbeatLoop(commands, events)
                // heartbeatLoop only returns when a connection has failed.
            } catch (t: Throwable) {
                _state.value = HeosConnectionState.Failed(host, t.message ?: t.toString())
            } finally {
                runCatching { eventConnection?.close() }
                runCatching { commandConnection?.close() }
                eventConnection = null
                commandConnection = null
                client = null
                _lastKnownPid = null
            }

            attempt++
            val backoffMs = (BASE_BACKOFF_MS * (1 shl attempt.coerceAtMost(5)))
                .coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs + Random.nextLong(JITTER_MS))
        }
    }

    /**
     * Watches both sockets, not just the command one - "Now Playing loses sync" was traced to the
     * event connection silently dying (a router NAT timeout, a WiFi blip that only clips one of the
     * two sockets) while `commands.isConnected` stayed true, since a closed local socket object says
     * nothing about whether the far end is still actually delivering anything. Once that happens,
     * `player_state_changed`/`_progress` events just stop arriving forever with no error anywhere -
     * the UI freezes on whatever it last knew, while the receiver itself keeps moving.
     *
     * `heart_beat` doubles as a liveness probe here: sending it and awaiting the reply on the *event*
     * connection (not just the command one) forces a real round trip on that exact socket, so a dead
     * one surfaces as a timeout/IOException instead of staying invisible. A failure here ends the
     * loop, which sends [runSession] into its existing reconnect-with-backoff path for both sockets.
     */
    private suspend fun heartbeatLoop(commands: HeosConnection, events: HeosConnection) {
        while (commands.isConnected && events.isConnected) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (!commands.isConnected || !events.isConnected) break
            runCatching { commands.command("system", "heart_beat") }.onFailure { return }
            runCatching { events.command("system", "heart_beat") }.onFailure { return }
        }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val JITTER_MS = 500L
    }
}
