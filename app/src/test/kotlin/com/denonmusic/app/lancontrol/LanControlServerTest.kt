package com.denonmusic.app.lancontrol

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class LanControlServerTest {

    private var server: LanControlServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun startServer(
        token: String? = "secret",
        handle: (LanControlRequest) -> LanControlResponse = { LanControlResponse.ok("""{"ok":true}""") },
    ): LanControlServer {
        val s = LanControlServer(authToken = { token }, handle = handle)
        // Port 0: an ephemeral port for the test, unlike the real fixed LAN_CONTROL_PORT - avoids
        // fighting other tests/processes for one specific port.
        assertTrue(s.start(freePort()))
        server = s
        return s
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(
        port: Int,
        method: String,
        path: String,
        tokenHeader: String? = "secret",
    ): HttpResponse {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val requestText = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                tokenHeader?.let { append("X-Lan-Control-Token: $it\r\n") }
                append("\r\n")
            }
            out.write(requestText.toByteArray(Charsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: ""
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon != -1) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                if (n == -1) break
                readTotal += n
            }
            return HttpResponse(status, headers, String(bodyChars, 0, readTotal))
        }
    }

    private data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

    @Test
    fun `a request with the correct token header reaches the handler`() {
        val s = startServer(handle = { LanControlResponse.ok("""{"hello":"world"}""") })

        val response = request(s.port, "GET", "/status")

        assertEquals(200, response.status)
        assertEquals("""{"hello":"world"}""", response.body)
        assertEquals("application/json; charset=utf-8", response.headers["content-type"])
    }

    @Test
    fun `a missing token is rejected with 401 before the handler ever runs`() {
        var handlerCalled = false
        val s = startServer(handle = { handlerCalled = true; LanControlResponse.ok("{}") })

        val response = request(s.port, "GET", "/status", tokenHeader = null)

        assertEquals(401, response.status)
        assertTrue(!handlerCalled)
    }

    @Test
    fun `a wrong token is rejected with 401`() {
        val s = startServer(token = "secret")

        val response = request(s.port, "GET", "/status", tokenHeader = "wrong")

        assertEquals(401, response.status)
    }

    @Test
    fun `no configured token rejects every request, even with one presented`() {
        val s = startServer(token = null)

        val response = request(s.port, "GET", "/status")

        assertEquals(401, response.status)
    }

    @Test
    fun `an unknown path reaches the handler, which can 404 it`() {
        val s = startServer(handle = { LanControlResponse.notFound() })

        val response = request(s.port, "GET", "/nonexistent")

        assertEquals(404, response.status)
    }

    @Test
    fun `query parameters are parsed and decoded for the handler`() {
        var seen: Map<String, String>? = null
        val s = startServer(handle = { req -> seen = req.query; LanControlResponse.ok("{}") })

        request(s.port, "POST", "/volume?level=42&note=a%20b")

        assertEquals(mapOf("level" to "42", "note" to "a b"), seen)
    }

    @Test
    fun `token can also be presented as a query parameter`() {
        val s = startServer(token = "secret")

        val response = request(s.port, "GET", "/status?token=secret", tokenHeader = null)

        assertEquals(200, response.status)
    }

    @Test
    fun `a handler exception becomes a 500 rather than killing the connection`() {
        val s = startServer(handle = { throw IllegalStateException("boom") })

        val response = request(s.port, "GET", "/status")

        assertEquals(500, response.status)
        assertTrue(response.body.contains("boom"))
    }

    @Test
    fun `GET slash serves the web UI without a token and never reaches the handler`() {
        var handlerCalled = false
        val s = startServer(handle = { handlerCalled = true; LanControlResponse.ok("{}") })

        val response = request(s.port, "GET", "/", tokenHeader = null)

        assertEquals(200, response.status)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertTrue(response.body.contains("<html"))
        assertTrue(!handlerCalled)
    }

    @Test
    fun `server reports its bound port and running state`() {
        val s = startServer()

        assertTrue(s.isRunning)
        assertTrue(s.port > 0)
    }

    @Test
    fun `close stops the server`() {
        val s = startServer()
        s.close()

        assertTrue(!s.isRunning)
    }

    @Test
    fun `constant time equals compares equal and unequal strings correctly`() {
        assertTrue(constantTimeEquals("secret", "secret"))
        assertTrue(!constantTimeEquals("secret", "secre1"))
        assertTrue(!constantTimeEquals("secret", "secrets"))
        assertTrue(!constantTimeEquals("", "x"))
        assertTrue(constantTimeEquals("", ""))
    }

    @Test
    fun `json string escapes control characters and quotes`() {
        assertEquals("\"hello\"", jsonString("hello"))
        assertEquals("null", jsonString(null))
        assertEquals("\"a\\\"b\"", jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", jsonString("a\\b"))
    }
}
