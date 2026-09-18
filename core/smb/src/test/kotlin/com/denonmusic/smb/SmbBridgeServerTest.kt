package com.denonmusic.smb

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(10)
class SmbBridgeServerTest {

    private var server: SmbBridgeServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun startServer(content: ByteArray, contentType: String = "audio/flac"): SmbBridgeServer {
        val resource = BridgeResource(
            length = content.size.toLong(),
            contentType = contentType,
            openAt = { offset -> content.inputStream().apply { skip(offset) } },
        )
        val s = SmbBridgeServer { token -> if (token == "track") resource else null }
        s.start()
        server = s
        return s
    }

    private fun request(port: Int, path: String, rangeHeader: String? = null): HttpResponse {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val requestText = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                rangeHeader?.let { append("Range: $it\r\n") }
                append("\r\n")
            }
            out.write(requestText.toByteArray(Charsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
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
    fun `plain GET returns the whole resource with a 200`() {
        val s = startServer("hello world".toByteArray())

        val response = request(s.port, "/track")

        assertEquals(200, response.status)
        assertEquals("hello world", response.body)
        assertEquals("11", response.headers["content-length"])
        assertEquals("audio/flac", response.headers["content-type"])
    }

    @Test
    fun `a ranged GET returns 206 with only the requested bytes`() {
        val s = startServer("0123456789".toByteArray())

        val response = request(s.port, "/track", rangeHeader = "bytes=2-4")

        assertEquals(206, response.status)
        assertEquals("234", response.body)
        assertEquals("bytes 2-4/10", response.headers["content-range"])
    }

    @Test
    fun `an open-ended range serves to the end of the file`() {
        val s = startServer("0123456789".toByteArray())

        val response = request(s.port, "/track", rangeHeader = "bytes=7-")

        assertEquals(206, response.status)
        assertEquals("789", response.body)
    }

    @Test
    fun `an unknown token returns 404`() {
        val s = startServer("data".toByteArray())

        val response = request(s.port, "/nonexistent")

        assertEquals(404, response.status)
    }

    @Test
    fun `an out-of-range request returns 416`() {
        val s = startServer("short".toByteArray())

        val response = request(s.port, "/track", rangeHeader = "bytes=100-200")

        assertEquals(416, response.status)
    }

    @Test
    fun `content type maps common audio extensions`() {
        assertEquals("audio/flac", contentTypeForPath("track.flac"))
        assertEquals("audio/mpeg", contentTypeForPath("track.mp3"))
        assertEquals("audio/x-dsd", contentTypeForPath("track.dsf"))
        assertEquals("audio/x-dff", contentTypeForPath("track.dff"))
        assertEquals("application/octet-stream", contentTypeForPath("track.unknown"))
    }

    @Test
    fun `supported audio file recognises only the parseable formats`() {
        assertTrue(isSupportedAudioFile("track.flac"))
        assertTrue(isSupportedAudioFile("Track.MP3"))
        assertTrue(isSupportedAudioFile("track.dsf"))
        assertTrue(isSupportedAudioFile("track.dff"))
        assertTrue(!isSupportedAudioFile("cover.jpg"))
        assertTrue(!isSupportedAudioFile("album.nfo"))
    }

    @Test
    fun `server binds an ephemeral port on start and reports it running`() {
        val s = startServer("x".toByteArray())

        assertTrue(s.isRunning)
        assertTrue(s.port > 0)
    }

    @Test
    fun `close stops the server`() {
        val s = startServer("x".toByteArray())
        s.close()

        assertTrue(!s.isRunning)
    }
}
