package com.denonmusic.app.lancontrol

import com.denonmusic.smb.HttpRequestReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Semaphore
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
 *
 * **Everything up to that token check runs for anyone who can reach the port**, so none of it may
 * trust its input: a request is read through [HttpRequestReader]'s size limits and under
 * [readTimeoutMillis], at most [MAX_CLIENTS] are served at once, and [serve] lets no exception
 * leave its thread. That last one is not tidiness. An exception escaping a plain thread ends the
 * whole process on Android, and a single `GET /status?x=%zz` used to do exactly that - with no
 * token, because the query string is decoded before the token is looked at.
 */
class LanControlServer(
    private val authToken: () -> String?,
    private val handle: (LanControlRequest) -> LanControlResponse,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : AutoCloseable {

    private val clientSlots = Semaphore(MAX_CLIENTS)
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
                    // Thread-per-connection with no ceiling is a thread per socket anyone cares to
                    // open. Turning the surplus away costs a real client nothing: this API's own
                    // traffic is a handful of short requests at a time.
                    if (!clientSlots.tryAcquire()) {
                        runCatching { client.close() }
                        continue
                    }
                    thread(isDaemon = true, name = "lan-control-client") {
                        try {
                            serve(client)
                        } finally {
                            clientSlots.release()
                        }
                    }
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
                // Bounds how long a client may take to *send* its request - without it a socket
                // that connects and says nothing holds its thread for good. It does not cut a slow
                // handler short: that is this side writing, which a read timeout never touches.
                socket.soTimeout = readTimeoutMillis
                val reader = HttpRequestReader(socket.getInputStream())
                val requestLine = reader.readLine() ?: return
                val headers = reader.readHeaders()
                val parsed = parseRequestLine(requestLine).takeUnless { reader.overflowed }
                    ?: return respond(socket, LanControlResponse(400, """{"error":"bad request"}"""))

                // The page itself carries no secrets and can't know the token before the user
                // types it in, so it's served without auth - same as a login screen. Every API
                // call it makes is still gated below like any other client.
                if (parsed.method == "GET" && parsed.path == "/") {
                    return respondHtml(socket, WEB_UI_HTML)
                }

                val presented = headers["x-lan-control-token"] ?: parsed.query["token"]
                val expected = authToken()
                if (expected.isNullOrEmpty() || presented == null || !constantTimeEquals(presented, expected)) {
                    return respond(socket, LanControlResponse(401, """{"error":"unauthorized"}"""))
                }

                val response = runCatching { handle(parsed) }
                    .getOrElse { e -> LanControlResponse(500, """{"error":${jsonString(e.message ?: "internal error")}}""") }
                respond(socket, response)
            } catch (_: IOException) {
                // Peer closed early, went quiet past the read timeout, or sent more than
                // HttpRequestReader will hold - nothing to answer.
            } catch (_: Exception) {
                // See the class doc: nothing may escape this thread.
                runCatching { respond(socket, LanControlResponse(500, """{"error":"internal error"}""")) }
            }
        }
    }

    /** Null for anything that isn't a usable request line, which the caller answers with a 400. */
    private fun parseRequestLine(line: String): LanControlRequest? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val queryIndex = rawPath.indexOf('?')
        val path = if (queryIndex == -1) rawPath else rawPath.substring(0, queryIndex)
        val query = if (queryIndex == -1) emptyMap() else parseQuery(rawPath.substring(queryIndex + 1)) ?: return null
        return LanControlRequest(method, path, query)
    }

    /**
     * Null when the query can't be trusted as written: a percent escape `URLDecoder` rejects (it
     * throws rather than passing the text through), or a control character in a decoded name or
     * value. The second matters because of where these values go next. Both receiver protocols are
     * line-based and neither escapes a line ending - `/input?mnemonic=NET%0DMV98` would have reached
     * the AVR's telnet port as `SINET` followed by a `MV98` nobody asked for, and a `cid` could
     * carry a second HEOS command the same way. Nothing this API accepts needs one.
     */
    private fun parseQuery(raw: String): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq == -1) continue
            val key = decodeOrNull(pair.substring(0, eq)) ?: return null
            val value = decodeOrNull(pair.substring(eq + 1)) ?: return null
            result[key] = value
        }
        return result
    }

    private fun decodeOrNull(text: String): String? =
        try {
            URLDecoder.decode(text, "UTF-8").takeUnless { decoded -> decoded.any(Char::isISOControl) }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun respond(socket: Socket, response: LanControlResponse) =
        writeResponse(socket, response.status, "application/json; charset=utf-8", response.body.toByteArray(Charsets.UTF_8))

    private fun respondHtml(socket: Socket, html: String) =
        writeResponse(socket, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))

    private fun writeResponse(socket: Socket, status: Int, contentType: String, bodyBytes: ByteArray) {
        val output = socket.getOutputStream()
        val headerLines = listOf(
            "HTTP/1.1 $status ${statusText(status)}",
            "Content-Type: $contentType",
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

    private companion object {
        const val READ_TIMEOUT_MILLIS = 10_000

        /** Well clear of the web UI's own polling plus a few scripts; see the accept loop. */
        const val MAX_CLIENTS = 32
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
