package com.denonmusic.heos

import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicLong

/**
 * A single socket to the HEOS CLI port.
 *
 * The protocol multiplexes command responses and unsolicited change events onto one stream, so this
 * class owns a reader loop that routes each frame to either a waiting caller or [events].
 *
 * A session normally runs two of these: one registered for change events, one for commands. Keeping
 * them apart means a burst of progress events cannot delay a command response, and a long browse
 * response cannot delay event delivery.
 */
class HeosConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 5_000,
    private val commandTimeoutMillis: Long = 15_000,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeLock = Mutex()
    private val pendingLock = Mutex()
    private val sequence = AtomicLong(0)

    private val pending = LinkedHashMap<Long, Pending>()

    private val _events = MutableSharedFlow<HeosFrame>(extraBufferCapacity = 256)
    val events: SharedFlow<HeosFrame> = _events.asSharedFlow()

    private val _disconnects = MutableSharedFlow<Throwable?>(extraBufferCapacity = 8)

    /** Emits when the reader loop stops, carrying the cause if it stopped because of an error. */
    val disconnects: SharedFlow<Throwable?> = _disconnects.asSharedFlow()

    private var socket: Socket? = null
    private var writer: Writer? = null
    private var readerJob: Job? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    private data class Pending(
        val command: String,
        val deferred: CompletableDeferred<HeosFrame>,
    )

    suspend fun connect() = withContext(dispatcher) {
        close()
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        // The receiver drops idle CLI sockets; the session layer heartbeats to keep this open, and
        // a read timeout here would fight that rather than help.
        newSocket.soTimeout = 0
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket = newSocket
        writer = newSocket.getOutputStream().writer(Charsets.UTF_8)
        readerJob = scope.launch { readLoop(newSocket) }
    }

    private suspend fun readLoop(activeSocket: Socket) {
        var failure: Throwable? = null
        try {
            val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                val frame = HeosFrameParser.parse(line) ?: continue
                if (frame.isEvent) {
                    _events.emit(frame)
                } else {
                    complete(frame)
                }
            }
        } catch (io: IOException) {
            failure = io
        } finally {
            failAllPending(failure ?: IOException("HEOS connection closed"))
            _disconnects.emit(failure)
        }
    }

    private suspend fun complete(frame: HeosFrame) {
        // Not a real answer - the caller's Pending entry (and its command timeout) must stay in
        // place for the actual result, which arrives later under the same sequence.
        if (frame.isCommandUnderProcess) return
        val waiter = pendingLock.withLock {
            val bySequence = frame.sequence?.let { pending.remove(it) }
            // Not every firmware echoes every argument back. Fall back to the oldest caller still
            // waiting on this exact command, which is correct because commands are written under a
            // lock and the receiver answers them in order.
            bySequence ?: pending.entries
                .firstOrNull { it.value.command == frame.command }
                ?.also { pending.remove(it.key) }
                ?.value
        }
        waiter?.deferred?.complete(frame)
    }

    private suspend fun failAllPending(cause: Throwable) {
        val waiters = pendingLock.withLock {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        waiters.forEach { it.deferred.completeExceptionally(cause) }
    }

    /**
     * Sends a command and suspends until its response arrives.
     *
     * Throws [HeosCommandException] when the receiver reports a failure, so callers do not have to
     * inspect every result field by hand.
     */
    suspend fun command(
        group: String,
        command: String,
        attributes: List<Pair<String, String>> = emptyList(),
    ): HeosFrame {
        val id = sequence.incrementAndGet()
        val qualified = "$group/$command"
        val deferred = CompletableDeferred<HeosFrame>()
        pendingLock.withLock { pending[id] = Pending(qualified, deferred) }

        val withSequence = attributes + (HeosProtocol.SEQUENCE to id.toString())
        val text = HeosProtocol.buildCommand(group, command, withSequence)

        try {
            writeLock.withLock {
                val activeWriter = writer ?: throw IOException("HEOS connection is not open")
                withContext(dispatcher) {
                    activeWriter.write(text + HeosProtocol.TERMINATOR)
                    activeWriter.flush()
                }
            }
            val frame = withTimeout(commandTimeoutMillis) { deferred.await() }
            frame.asError()?.let { throw HeosCommandException(it) }
            return frame
        } catch (t: Throwable) {
            pendingLock.withLock { pending.remove(id) }
            throw t
        }
    }

    override fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    /** Releases the internal scope. The instance cannot be reconnected afterwards. */
    fun shutdown() {
        close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_PORT: Int = 1255
    }
}
