package com.denonmusic.plex

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives [PlexClient] against a real loopback [HttpServer] - the JDK's own built-in one, not a new
 * dependency, same "fake over a real socket" approach [com.denonmusic.heos.HeosClientTest] (and
 * everything that uses `FakeHeosServer`) already takes for the sibling protocol modules.
 *
 * No test here is run against a real Plex server - see [PlexClient]'s own doc on why the response
 * shape is unverified. These fixtures are this project's best understanding of Plex's JSON from its
 * community-documented API, not a captured real response; a mismatch found against real hardware
 * belongs here as a corrected fixture, same as `docs/local-setup.md` already does for HEOS.
 */
@Timeout(30)
class PlexClientTest {

    private lateinit var server: HttpServer
    private var lastRequestHeaders: com.sun.net.httpserver.Headers? = null
    private var lastRequestPath: String? = null
    private var responseStatus = 200
    private var responseBody = "{}"

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            lastRequestHeaders = exchange.requestHeaders
            lastRequestPath = exchange.requestURI.toString()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun client() = PlexClient("http://127.0.0.1:${server.address.port}", token = "test-token")

    @Test
    fun `sends the token and identification headers on every request`() = runBlocking {
        responseBody = """{"MediaContainer":{"Metadata":[]}}"""
        client().listPlaylists()

        assertEquals("test-token", lastRequestHeaders?.getFirst("X-Plex-Token"))
        assertEquals("application/json", lastRequestHeaders?.getFirst("Accept"))
        assertTrue(lastRequestHeaders?.getFirst("X-Plex-Client-Identifier")?.isNotBlank() == true)
        assertTrue(lastRequestPath?.startsWith("/playlists") == true)
    }

    @Test
    fun `parses playlists, skipping a row with no ratingKey or title`() = runBlocking {
        responseBody = """
            {"MediaContainer":{"Metadata":[
                {"ratingKey":"1","title":"Driving","leafCount":12},
                {"ratingKey":"2","title":"No count"},
                {"title":"No ratingKey"},
                {"ratingKey":"3"}
            ]}}
        """.trimIndent()

        val playlists = client().listPlaylists()

        assertEquals(2, playlists.size)
        assertEquals(PlexPlaylist(ratingKey = "1", title = "Driving", itemCount = 12), playlists[0])
        assertEquals(PlexPlaylist(ratingKey = "2", title = "No count", itemCount = 0), playlists[1])
    }

    @Test
    fun `parses playlist items in the server's own order, with artist and album`() = runBlocking {
        responseBody = """
            {"MediaContainer":{"Metadata":[
                {"ratingKey":"101","title":"Rolling in the Deep","grandparentTitle":"Adele","parentTitle":"21","duration":228000},
                {"ratingKey":"102","title":"Someone Like You","grandparentTitle":"Adele","parentTitle":"21","duration":285000}
            ]}}
        """.trimIndent()

        val items = client().playlistItems("55")

        assertEquals("/playlists/55/items", lastRequestPath)
        assertEquals(2, items.size)
        assertEquals(
            PlexPlaylistItem(ratingKey = "101", title = "Rolling in the Deep", artist = "Adele", album = "21", durationMs = 228_000),
            items[0],
        )
        assertEquals("Someone Like You", items[1].title)
    }

    @Test
    fun `missing artist or album comes back null, not a crash`() = runBlocking {
        responseBody = """{"MediaContainer":{"Metadata":[{"ratingKey":"9","title":"Untitled"}]}}"""

        val items = client().playlistItems("1")

        assertNull(items[0].artist)
        assertNull(items[0].album)
        assertNull(items[0].durationMs)
    }

    @Test
    fun `a 401 response is reported as PlexApiException, not swallowed as an empty list`() = runBlocking {
        responseStatus = 401
        responseBody = "Unauthorized"

        try {
            client().listPlaylists()
            fail("expected PlexApiException")
        } catch (e: PlexApiException) {
            assertEquals(401, e.statusCode)
        }
    }
}
