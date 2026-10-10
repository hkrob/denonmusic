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
 * No test here is run against a real Plex server, but every fixture in this file is a response shape
 * this project did see from one: the read side (`listPlaylists`/`playlistItems`) and the write side
 * added here (`identity`/`musicSectionKey`/`findLibraryTracks`/`addToPlaylist`/`createPlaylist`) were
 * each checked field-by-field against a real authenticated server (2026-10-10/11, see
 * docs/local-setup.md) before being written the way they are below - including creating and deleting
 * a throwaway test playlist to see the write responses for real. `ignoreUnknownKeys` stays on
 * regardless, since Plex's schema is still undocumented outside its community wiki and only the
 * fields this app reads were ever modelled.
 */
@Timeout(30)
class PlexClientTest {

    private lateinit var server: HttpServer
    private var lastRequestHeaders: com.sun.net.httpserver.Headers? = null
    private var lastRequestPath: String? = null
    private var lastRequestMethod: String? = null
    private var responseStatus = 200
    private var responseBody = "{}"

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            lastRequestHeaders = exchange.requestHeaders
            lastRequestPath = exchange.requestURI.toString()
            lastRequestMethod = exchange.requestMethod
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

    @Test
    fun `identity reads machineIdentifier off MediaContainer directly, not a Metadata row`() = runBlocking {
        responseBody = """{"MediaContainer":{"size":0,"apiVersion":"1.2.3","claimed":"1",""" +
            """"machineIdentifier":"1811ecfc72fa401e87c7e8575a5b03053cc8c66e","version":"1.43.4"}}"""

        assertEquals("1811ecfc72fa401e87c7e8575a5b03053cc8c66e", client().identity())
        assertEquals("/identity", lastRequestPath)
    }

    @Test
    fun `musicSectionKey picks the Directory entry whose type is artist`() = runBlocking {
        responseBody = """
            {"MediaContainer":{"Directory":[
                {"key":"1","type":"movie","title":"Movies"},
                {"key":"3","type":"artist","title":"Music"},
                {"key":"2","type":"show","title":"TV Shows"}
            ]}}
        """.trimIndent()

        assertEquals("3", client().musicSectionKey())
    }

    @Test
    fun `findLibraryTracks parses matches the same way playlist items parse`() = runBlocking {
        responseBody = """
            {"MediaContainer":{"Metadata":[
                {"ratingKey":"4492","title":"Manic Monday","grandparentTitle":"Prince","parentTitle":"Originals"},
                {"ratingKey":"7688","title":"Manic Monday (Unreleased)","grandparentTitle":"Various Artists","parentTitle":"Prince Misc"}
            ]}}
        """.trimIndent()

        val tracks = client().findLibraryTracks("3", "Manic Monday")

        assertEquals("/library/sections/3/all?type=10&title=Manic+Monday", lastRequestPath)
        assertEquals(2, tracks.size)
        assertEquals(PlexLibraryTrack("4492", "Manic Monday", "Prince", "Originals"), tracks[0])
    }

    @Test
    fun `addToPlaylist PUTs a comma-joined uri and reads leafCountAdded off MediaContainer`() = runBlocking {
        responseBody = """{"MediaContainer":{"size":1,"leafCountAdded":2,"leafCountRequested":2,""" +
            """"Metadata":[{"ratingKey":"48567","title":"test"}]}}"""

        val added = client().addToPlaylist("48567", machineId = "abc123", itemRatingKeys = listOf("26110", "26258"))

        assertEquals(2, added)
        assertEquals("PUT", lastRequestMethod)
        assertEquals(
            "/playlists/48567/items?uri=server%3A%2F%2Fabc123%2Fcom.plexapp.plugins.library%2Flibrary%2Fmetadata%2F26110%2C26258",
            lastRequestPath,
        )
    }

    @Test
    fun `createPlaylist POSTs and returns the playlist Plex reports back`() = runBlocking {
        responseBody = """{"MediaContainer":{"size":1,"Metadata":[""" +
            """{"ratingKey":"48567","title":"denonmusic-test-delete-me","leafCount":1}]}}"""

        val playlist = client().createPlaylist("denonmusic-test-delete-me", machineId = "abc123", itemRatingKeys = listOf("4492"))

        assertEquals(PlexPlaylist("48567", "denonmusic-test-delete-me", 1), playlist)
        assertEquals("POST", lastRequestMethod)
        assertTrue(lastRequestPath?.startsWith("/playlists?type=audio&smart=0&title=") == true)
    }

    @Test
    fun `addToPlaylist refuses an empty list rather than making a nonsense request`(): Unit = runBlocking {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            client().addToPlaylist("1", machineId = "abc", itemRatingKeys = emptyList())
        }
    }
}
