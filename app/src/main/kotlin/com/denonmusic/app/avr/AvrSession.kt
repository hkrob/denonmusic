package com.denonmusic.app.avr

import com.denonmusic.avr.AvrClient
import com.denonmusic.avr.AvrConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

sealed interface AvrConnectionState {
    data object Disconnected : AvrConnectionState
    data object Connecting : AvrConnectionState
    data class Connected(val host: String) : AvrConnectionState
    data class Failed(val host: String, val reason: String) : AvrConnectionState
}

/**
 * Owns the single telnet socket to the AVR control port and reconnects it with backoff, the same
 * shape as [com.denonmusic.app.heos.HeosSession] but for one socket instead of two - this protocol
 * has no separate event registration handshake, so there's nothing to split.
 */
@Singleton
class AvrSession @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connection: AvrConnection? = null
    private var client: AvrClient? = null
    private var sessionJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow<AvrConnectionState>(AvrConnectionState.Disconnected)
    val state: StateFlow<AvrConnectionState> = _state.asStateFlow()

    val avrClient: AvrClient? get() = client
    val events: SharedFlow<String>? get() = connection?.events

    fun start(host: String) {
        if (_state.value.let { it is AvrConnectionState.Connected && it.host == host }) return
        sessionJob?.cancel()
        sessionJob = scope.launch { runSession(host) }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        connection?.shutdown()
        client = null
        _state.value = AvrConnectionState.Disconnected
    }

    private suspend fun runSession(host: String) {
        var attempt = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            _state.value = AvrConnectionState.Connecting
            try {
                val newConnection = AvrConnection(host)
                newConnection.connect()
                connection = newConnection
                client = AvrClient(newConnection)
                attempt = 0
                _state.value = AvrConnectionState.Connected(host)

                // Suspends until the reader loop ends (socket closed or an IOException), then falls
                // through to the reconnect-with-backoff below - the same shape as HeosSession's
                // heartbeat loop, just waiting on the connection's own failure signal instead.
                val failure = newConnection.disconnects.first()
                _state.value = AvrConnectionState.Failed(host, failure?.message ?: "disconnected")
            } catch (t: Throwable) {
                _state.value = AvrConnectionState.Failed(host, t.message ?: t.toString())
            } finally {
                // shutdown, not close: AvrConnection owns a CoroutineScope that close() leaves
                // running, so a reconnect loop on a flaky network leaked one per attempt.
                runCatching { connection?.shutdown() }
                connection = null
                client = null
            }

            attempt++
            val backoffMs = (BASE_BACKOFF_MS * (1 shl attempt.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs + Random.nextLong(JITTER_MS))
        }
    }

    companion object {
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val JITTER_MS = 500L
    }
}
