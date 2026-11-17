package com.denonmusic.heos

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal stand-in for a HEOS receiver, speaking the real protocol over a real loopback socket.
 *
 * Using an actual socket rather than a mocked transport means the tests exercise framing, CRLF
 * termination and the reader loop the same way production does. Responders are matched against the
 * `heos://group/command` prefix of the incoming line and are handed the full line so they can echo
 * the SEQUENCE argument back, exactly as a receiver does.
 */
class FakeHeosServer : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()
    private val responders = LinkedHashMap<String, (String) -> List<String>>()

    /** Every command line the server received, in arrival order. */
    val received: MutableList<String> = CopyOnWriteArrayList()

    val port: Int get() = server.localPort
    val host: String get() = server.inetAddress.hostAddress

    init {
        thread(isDaemon = true, name = "fake-heos-accept") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients += socket
                thread(isDaemon = true, name = "fake-heos-client") { serve(socket) }
            }
        }
    }

    /** Registers a handler for `heos://<commandPath>`, returning the raw JSON lines to write back. */
    fun on(commandPath: String, responder: (line: String) -> List<String>) {
        responders[commandPath] = responder
    }

    /** Convenience for a fixed single-line response. */
    fun onJson(commandPath: String, json: String) = on(commandPath) { listOf(json) }

    /**
     * Responds to [commandPath] with a success frame that echoes the caller's SEQUENCE, which is
     * what correlation depends on.
     */
    fun onSuccess(commandPath: String, message: String = "", payload: String? = null) =
        on(commandPath) { line ->
            val parts = listOfNotNull(
                message.takeIf { it.isNotEmpty() },
                sequenceArgOf(line)?.let { "${HeosProtocol.SEQUENCE}=$it" },
            )
            // Frames are newline delimited on the wire, so a pretty-printed payload written
            // verbatim would be read back as several truncated frames. Receivers send compact JSON.
            val compactPayload = payload?.replace(Regex("\\n\\s*"), "")
            val payloadPart = compactPayload?.let { ""","payload":$it""" }.orEmpty()
            listOf(
                """{"heos":{"command":"$commandPath","result":"success",""" +
                    """"message":"${parts.joinToString("&")}"}$payloadPart}""",
            )
        }

    /** Pushes an unsolicited event to every connected client. */
    fun emit(json: String) {
        clients.filter { !it.isClosed }.forEach { socket ->
            runCatching {
                socket.getOutputStream().apply {
                    write((json + HeosProtocol.TERMINATOR).toByteArray())
                    flush()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        while (!socket.isClosed) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            received += line
            val path = line.removePrefix("heos://").substringBefore('?')
            val responder = responders[path] ?: continue
            responder(line).forEach { response ->
                runCatching {
                    output.write((response + HeosProtocol.TERMINATOR).toByteArray())
                    output.flush()
                }
            }
        }
        runCatching { socket.close() }
    }

    override fun close() {
        clients.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    companion object {
        fun sequenceArgOf(line: String): String? =
            HeosProtocol.parseMessage(line.substringAfter('?', ""))[HeosProtocol.SEQUENCE]
    }
}
