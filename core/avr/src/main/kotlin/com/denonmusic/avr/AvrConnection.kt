package com.denonmusic.avr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One telnet socket to the Denon AVR control port.
 *
 * Unlike HEOS, this protocol has no correlation id: a query like `SI?` and an unsolicited state
 * change both come back as a bare line starting with `SI`. The only way to tell "the answer to my
 * query" from "the receiver changed on its own" is response-prefix matching, on the assumption that
 * the receiver answers a query before anything else races ahead of it - true in practice because
 * nothing else on the wire is likely to reply with the exact same prefix within the same instant.
 *
 * Every line - matched or not - is also published on [events], so a session layer can drive live UI
 * (volume, sound mode, output map) from state changes the user made with the physical remote, not
 * just from this app's own commands.
 */
class AvrConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 5_000,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeLock = Mutex()
    private val pendingLock = Mutex()
    private val pending = mutableListOf<Pending>()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _disconnects = MutableSharedFlow<Throwable?>(extraBufferCapacity = 8)
    val disconnects: SharedFlow<Throwable?> = _disconnects.asSharedFlow()

    private var socket: Socket? = null
    private var writer: Writer? = null
    private var readerJob: Job? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * [collected] is appended to from the reader coroutine and read from the waiting caller's own
     * coroutine - different threads on [Dispatchers.IO], so a bare `mutableListOf` here could tear a
     * read or throw `ConcurrentModificationException` mid-iteration.
     */
    private class Pending(val matches: (String) -> Boolean) {
        private val collected = mutableListOf<String>()

        fun add(line: String) = synchronized(collected) { collected += line }

        fun snapshot(): List<String> = synchronized(collected) { collected.toList() }
    }

    suspend fun connect() = withContext(dispatcher) {
        close()
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.soTimeout = 0
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket = newSocket
        writer = newSocket.getOutputStream().writer(Charsets.US_ASCII)
        readerJob = scope.launch { readLoop(newSocket) }
    }

    private suspend fun readLoop(activeSocket: Socket) {
        var failure: Throwable? = null
        try {
            val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.US_ASCII))
            while (scope.isActive) {
                val line = reader.readLine()?.trim() ?: break
                if (line.isEmpty()) continue
                _events.emit(line)
                dispatchToWaiters(line)
            }
        } catch (io: IOException) {
            failure = io
        } finally {
            _disconnects.emit(failure)
        }
    }

    private suspend fun dispatchToWaiters(line: String) {
        pendingLock.withLock {
            pending.filter { it.matches(line) }.forEach { it.add(line) }
        }
    }

    /** Sends a bare command with no reply expected, e.g. `PWON`, `SINET`, `MSDIRECT`. */
    suspend fun send(command: String) {
        writeLock.withLock {
            val activeWriter = writer ?: throw IOException("AVR connection is not open")
            withContext(dispatcher) {
                activeWriter.write(command + TERMINATOR)
                activeWriter.flush()
            }
        }
    }

    /**
     * Sends [command] and returns the first line whose prefix matches [responsePrefix].
     *
     * Used for simple one-line answers: `PW?` -> `PWON`, `SI?` -> `SINET`, `MS?` -> `MSSTEREO`.
     */
    suspend fun query(command: String, responsePrefix: String, timeoutMillis: Long = 3_000): String =
        queryFirstMatching(command, timeoutMillis) { it.startsWith(responsePrefix) }

    /**
     * [query] with an arbitrary predicate instead of a bare prefix, for the cases where a prefix
     * alone would also catch one of the receiver's own free-running telemetry lines - `MV?`'s answer
     * shares its `MV` prefix with the `MVMAX` line this unit emits unprompted, for instance.
     */
    suspend fun queryFirstMatching(
        command: String,
        timeoutMillis: Long = 3_000,
        matches: (String) -> Boolean,
    ): String = queryMatching(command, matches, timeoutMillis) { it.snapshot().isNotEmpty() }.first()

    /**
     * Sends [command] and collects every line starting with any of [responsePrefixes], for a fixed
     * [burstWindowMillis] measured from the *first* matching line - not "until it goes quiet."
     *
     * A real AVR-X4500H probed for this project turned out to free-run a status telemetry block
     * (`SSINF*`/`CV*`/`MVMAX`/`DCAUTO`) roughly once a second on its own, independent of anything this
     * client asks for - so a quiet-period read can starve forever waiting for a silence that never
     * comes. A fixed window anchored to the first hit sidesteps that: it needs only that this
     * command's own answer (e.g. `SSINFAISSIG ?`'s numeric code line plus the receiver's own
     * human-readable `SYSDA` label line) arrives close together, not that nothing else ever arrives
     * again.
     */
    suspend fun queryMatchingAny(
        command: String,
        responsePrefixes: Set<String>,
        burstWindowMillis: Long = 150,
        overallTimeoutMillis: Long = 2_000,
    ): List<String> = queryMatching(
        command,
        { line -> responsePrefixes.any { line.startsWith(it) } },
        overallTimeoutMillis,
    ) { pendingEntry ->
        if (pendingEntry.snapshot().isEmpty()) {
            false
        } else {
            kotlinx.coroutines.delay(burstWindowMillis)
            true
        }
    }

    /**
     * Sends [command] and collects every line starting with [responsePrefix] up to and including the
     * first line starting with [terminator].
     *
     * `CV?`'s reply is exactly this shape: one `CV<channel> <level>` line per currently-active output
     * channel, followed by a literal `CVEND` line - confirmed against a real AVR-X4500H, and the only
     * reliable stop signal given the same receiver's tendency to free-run further `CV*` telemetry
     * afterwards that a quiet-period read would otherwise wait on forever.
     */
    suspend fun queryUntilTerminator(
        command: String,
        responsePrefix: String,
        terminator: String,
        overallTimeoutMillis: Long = 3_000,
    ): List<String> = queryMatching(command, { it.startsWith(responsePrefix) }, overallTimeoutMillis) { pendingEntry ->
        pendingEntry.snapshot().any { it.startsWith(terminator) }
    }

    private suspend fun queryMatching(
        command: String,
        matches: (String) -> Boolean,
        timeoutMillis: Long,
        isDone: suspend (Pending) -> Boolean,
    ): List<String> {
        val entry = Pending(matches)
        pendingLock.withLock { pending += entry }
        try {
            writeLock.withLock {
                val activeWriter = writer ?: throw IOException("AVR connection is not open")
                withContext(dispatcher) {
                    activeWriter.write(command + TERMINATOR)
                    activeWriter.flush()
                }
            }
            return withTimeout(timeoutMillis) {
                while (!isDone(entry)) {
                    kotlinx.coroutines.delay(POLL_INTERVAL_MILLIS)
                }
                entry.snapshot()
            }
        } finally {
            pendingLock.withLock { pending.remove(entry) }
        }
    }

    /**
     * Drops the socket and the reader job, leaving the instance reusable by [connect].
     *
     * **Not the one to call when you are finished with a connection**: it deliberately leaves
     * [scope] alive so the instance can reconnect, so a caller that discards the object afterwards
     * leaks the scope. Being the [AutoCloseable] override makes it the one every reflex reaches
     * for, which is how the sessions leaked connections per reconnect up to 0.1.8. Use [shutdown]
     * unless a [connect] is coming.
     */
    override fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    /**
     * [close], and then releases the internal scope. The instance cannot be reconnected afterwards.
     *
     * What anything owning a connection's whole lifetime should call.
     */
    fun shutdown() {
        close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_PORT: Int = 23

        /** The AVR protocol terminates commands with a bare CR, not CRLF. */
        const val TERMINATOR: String = "\r"
        private const val POLL_INTERVAL_MILLIS = 20L
    }
}
