package com.denonmusic.avr

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal stand-in for the Denon AVR telnet control port: bare-CR-terminated lines, no framing,
 * responders matched by the exact command text (including the `?`).
 */
class FakeAvrServer : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()
    private val responders = LinkedHashMap<String, (String) -> List<String>>()

    val received: MutableList<String> = CopyOnWriteArrayList()

    val port: Int get() = server.localPort
    val host: String get() = server.inetAddress.hostAddress

    init {
        thread(isDaemon = true, name = "fake-avr-accept") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients += socket
                thread(isDaemon = true, name = "fake-avr-client") { serve(socket) }
            }
        }
    }

    fun on(command: String, responder: (String) -> List<String>) {
        responders[command] = responder
    }

    fun onLines(command: String, vararg lines: String) = on(command) { lines.toList() }

    /** Pushes an unsolicited line to every connected client, as if the physical remote were used. */
    fun emit(line: String) {
        clients.filter { !it.isClosed }.forEach { socket ->
            runCatching {
                socket.getOutputStream().apply {
                    write((line + AvrConnection.TERMINATOR).toByteArray())
                    flush()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        while (!socket.isClosed) {
            val line = runCatching { reader.readLine() }.getOrNull()?.trim() ?: break
            received += line
            val responder = responders[line] ?: continue
            responder(line).forEach { response ->
                runCatching {
                    output.write((response + AvrConnection.TERMINATOR).toByteArray())
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
}
