package com.denonmusic.app.lancontrol

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

data class LanControlRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
)

data class LanControlResponse(val status: Int, val body: String) {
    companion object {
        fun ok(body: String) = LanControlResponse(200, body)
        fun notFound() = LanControlResponse(404, """{"error":"not found"}""")
    }
}

/**
 * A small always-JSON HTTP server for remote control from other devices on the LAN - a third-party
 * script or dashboard driving playback without opening the app. Deliberately the same shape as
 * [com.denonmusic.smb.SmbBridgeServer]: raw [ServerSocket], thread-per-connection, no external HTTP
 * library. A control API is low-traffic by nature (a handful of requests a minute at most, never a
 * media stream), so that simplicity is a better fit here than it would be for a busier server.
 *
 * Binds a **fixed** port rather than an OS-assigned one (unlike the bridge server): the whole point of
 * a LAN control API is that another device can hard-code `http://<phone-ip>:<port>/...` once, so an
 * ephemeral port that changes every app launch would defeat it.
 *
 * Every request must carry [authToken] via the `X-Lan-Control-Token` header or a `?token=` query
 * parameter; anything else (including a request while no token is configured) gets a 401. Comparison
 * is constant-time so response timing can't leak how many leading characters of a guess were correct.
 */
class LanControlServer(
    private val authToken: () -> String?,
    private val handle: (LanControlRequest) -> LanControlResponse,
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: -1
    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** @return true if the socket bound successfully. */
    fun start(port: Int): Boolean {
        if (isRunning) return true
        val socket = ServerSocket()
        socket.reuseAddress = true
        return try {
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket
            acceptThread = thread(isDaemon = true, name = "lan-control-accept") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "lan-control-client") { serve(client) }
                }
            }
            true
        } catch (_: IOException) {
            runCatching { socket.close() }
            false
        }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parsed = parseRequestLine(requestLine) ?: return respond(socket, LanControlResponse(400, """{"error":"bad request"}"""))
                val headers = readHeaders(reader)

                val presented = headers["x-lan-control-token"] ?: parsed.query["token"]
                val expected = authToken()
                if (expected.isNullOrEmpty() || presented == null || !constantTimeEquals(presented, expected)) {
                    return respond(socket, LanControlResponse(401, """{"error":"unauthorized"}"""))
                }

                val response = runCatching { handle(parsed) }
                    .getOrElse { e -> LanControlResponse(500, """{"error":${jsonString(e.message ?: "internal error")}}""") }
                respond(socket, response)
            } catch (_: IOException) {
                // Peer closed early - nothing to do.
            }
        }
    }

    private fun parseRequestLine(line: String): LanControlRequest? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val queryIndex = rawPath.indexOf('?')
        val path = if (queryIndex == -1) rawPath else rawPath.substring(0, queryIndex)
        val query = if (queryIndex == -1) emptyMap() else parseQuery(rawPath.substring(queryIndex + 1))
        return LanControlRequest(method, path, query)
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            key to value
        }.toMap()

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon == -1) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }

    private fun respond(socket: Socket, response: LanControlResponse) {
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val statusText = statusText(response.status)
        val output = socket.getOutputStream()
        val headerLines = listOf(
            "HTTP/1.1 ${response.status} $statusText",
            "Content-Type: application/json; charset=utf-8",
            "Content-Length: ${bodyBytes.size}",
            "Connection: close",
        )
        output.write((headerLines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }
}

/** Same length comparison run regardless of where the first mismatch is, so timing can't leak it. */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}

/** Minimal JSON string escaping - this server only ever emits a handful of known-shape objects. */
fun jsonString(value: String?): String {
    if (value == null) return "null"
    val escaped = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
    return "\"$escaped\""
}
