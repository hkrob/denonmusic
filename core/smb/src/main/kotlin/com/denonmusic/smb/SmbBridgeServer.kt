package com.denonmusic.smb

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The plan's phase-6 escape hatch: "the phone serves SMB over Range-capable HTTP via
 * `browse/play_stream?url=`" for the one case the primary architecture can't cover - no HEOS-indexed
 * source (DLNA server or HEOS SMB network share) registered yet. This is deliberately the *only*
 * piece of the audio path that ever runs on the phone, and deliberately minimal: one file per
 * request, `Connection: close`, thread-per-connection. A receiver fetching a single track is the
 * only load this ever needs to survive - it is not a general-purpose media server.
 *
 * Binds `0.0.0.0` (all interfaces), not loopback: the whole point is that the AVR, on the same LAN,
 * can reach it.
 */
class SmbBridgeServer(private val resolve: (token: String) -> BridgeResource?) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: -1
    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", 0))
        serverSocket = socket
        acceptThread = thread(isDaemon = true, name = "smb-bridge-accept") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "smb-bridge-client") { serve(client) }
            }
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
                val (method, path) = parseRequestLine(requestLine) ?: return respond(socket, 400, "Bad Request")
                val headers = readHeaders(reader)
                if (method != "GET") return respond(socket, 405, "Method Not Allowed")

                val token = path.removePrefix("/")
                val resource = resolve(token) ?: return respond(socket, 404, "Not Found")
                val range = headers["range"]?.let { parseRange(it, resource.length) }

                if (headers["range"] != null && range == null) {
                    return respond(socket, 416, "Range Not Satisfiable")
                }

                if (range == null) {
                    sendBody(socket, status = 200, resource = resource, start = 0, end = resource.length - 1)
                } else {
                    sendBody(socket, status = 206, resource = resource, start = range.first, end = range.second)
                }
            } catch (_: IOException) {
                // Peer closed early (a receiver aborting a range fetch mid-stream) - nothing to do.
            }
        }
    }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        return parts[0].uppercase() to parts[1]
    }

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

    /** Parses a single-range `Range: bytes=start-end` header; only the one form this app ever sends. */
    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
        val spec = header.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash == -1) return null
        val startText = spec.substring(0, dash)
        val endText = spec.substring(dash + 1)
        val start = startText.toLongOrNull() ?: return null
        val end = endText.toLongOrNull() ?: (totalLength - 1)
        if (start < 0 || end < start || start >= totalLength) return null
        return start to end.coerceAtMost(totalLength - 1)
    }

    private fun sendBody(socket: Socket, status: Int, resource: BridgeResource, start: Long, end: Long) {
        val contentLength = end - start + 1
        val output = BufferedOutputStream(socket.getOutputStream())
        val statusText = if (status == 206) "Partial Content" else "OK"
        val headerLines = buildList {
            add("HTTP/1.1 $status $statusText")
            add("Content-Type: ${resource.contentType}")
            add("Content-Length: $contentLength")
            add("Accept-Ranges: bytes")
            add("Connection: close")
            if (status == 206) add("Content-Range: bytes $start-$end/${resource.length}")
        }
        output.write((headerLines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
        resource.openAt(start).use { input -> copyExactly(input, output, contentLength) }
        output.flush()
    }

    private fun respond(socket: Socket, status: Int, statusText: String) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status $statusText\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n == -1) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }
}
