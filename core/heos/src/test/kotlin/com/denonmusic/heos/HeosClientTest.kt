package com.denonmusic.heos

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end tests over a real loopback socket against [FakeHeosServer].
 *
 * These use [runBlocking] rather than `runTest` on purpose: the client's command timeout is real
 * wall-clock time, and `runTest`'s virtual clock would skip straight past it before the socket had a
 * chance to answer. A JUnit timeout keeps a genuine hang from wedging the build.
 */
@Timeout(30)
class HeosClientTest {

    private lateinit var server: FakeHeosServer
    private lateinit var connection: HeosConnection
    private lateinit var client: HeosClient

    @BeforeEach
    fun setUp() {
        server = FakeHeosServer()
        connection = HeosConnection(server.host, server.port, commandTimeoutMillis = 5_000)
        client = HeosClient(connection)
    }

    @AfterEach
    fun tearDown() {
        connection.shutdown()
        server.close()
    }

    @Test
    fun `sends a heartbeat and matches the response`() = runBlocking {
        server.onSuccess("system/heart_beat")
        connection.connect()

        val frame = client.heartBeat()

        assertTrue(frame.isSuccess)
        assertTrue(server.received.single().startsWith("heos://system/heart_beat?"))
    }

    @Test
    fun `reads music sources and identifies the local media source`() = runBlocking {
        server.onSuccess(
            "browse/get_music_sources",
            payload = """[
                {"name":"Pandora","image_url":"http://x/p.png","type":"music_service","sid":"1","available":"true"},
                {"name":"Local Music","image_url":"http://x/l.png","type":"heos_server","sid":"1024","available":"true"}
            ]""".trimIndent(),
        )
        connection.connect()

        val sources = client.getMusicSources()

        assertEquals(2, sources.size)
        val local = assertNotNull(sources.firstOrNull { it.isLocalMedia })
        assertEquals("1024", local.sid)
        assertFalse(sources.first().isLocalMedia)
    }

    @Test
    fun `browse reports a playable container so play-all can be offered`() = runBlocking {
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=1&count=1&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[{"container":"no","playable":"yes","type":"song","name":"Track","mid":"m1"}],""" +
                    """"options":[{"browse":[{"id":21,"name":"Play All"}]}]}""",
            )
        }
        connection.connect()

        val page = client.browse(sid = "1024", cid = "Album/1")

        assertTrue(page.isPlayableContainer)
        assertEquals(1, page.count)
        assertTrue(page.items.single().isTrack)
    }

    @Test
    fun `browse decodes XML entities a DLNA source leaves in the raw title`() = runBlocking {
        // User-reported: a real DLNA-indexed track's title showed up as "Girls &amp; Boys" instead
        // of "Girls & Boys" - the receiver forwards its DIDL-Lite <dc:title> text without decoding
        // the entities XML itself required there in the first place.
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=1&count=1&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[{"container":"no","playable":"yes","type":"song","name":"Girls &amp; Boys","mid":"m1"}]}""",
            )
        }
        connection.connect()

        val page = client.browse(sid = "1024", cid = "Album/1")

        assertEquals("Girls & Boys", page.items.single().name)
    }

    @Test
    fun `browseAll keeps paging when the receiver reports an unknown container size`() = runBlocking {
        // count=0 means "size unknown": the only valid stop signal is an empty page, so a client
        // that loops to count would stop immediately and show nothing.
        var call = 0
        server.on("browse/browse") { line ->
            val sequence = FakeHeosServer.sequenceArgOf(line)
            val payload = when (call++) {
                0 -> (1..2).joinToString(",") { """{"container":"no","playable":"yes","type":"song","name":"T$it","mid":"m$it"}""" }
                1 -> (3..4).joinToString(",") { """{"container":"no","playable":"yes","type":"song","name":"T$it","mid":"m$it"}""" }
                else -> ""
            }
            val returned = if (payload.isEmpty()) 0 else 2
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=$returned&count=0&${HeosProtocol.SEQUENCE}=$sequence"},""" +
                    """"payload":[$payload]}""",
            )
        }
        connection.connect()

        val items = client.browseAll(sid = "1024", cid = "Big/Folder", pageSize = 2).toList()
            .flatMap { it.items }

        assertEquals(listOf("T1", "T2", "T3", "T4"), items.map { it.name })
    }

    @Test
    fun `browseAll stops on a short page without asking for another`() = runBlocking {
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=1&count=0&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[{"container":"yes","playable":"yes","type":"container","name":"Only","cid":"c1"}]}""",
            )
        }
        connection.connect()

        val pages = client.browseAll(sid = "1024", pageSize = 50).toList()

        assertEquals(1, pages.size)
        assertEquals(1, server.received.count { it.startsWith("heos://browse/browse") })
    }

    @Test
    fun `add to queue sends the add criteria as its protocol number`() = runBlocking {
        server.onSuccess("browse/add_to_queue")
        connection.connect()

        client.addToQueue(pid = "1", sid = "1024", cid = "Album/1", mid = "m1", criteria = AddCriteria.AddToEnd)

        val line = server.received.single()
        assertTrue(line.contains("aid=3"), "AddToEnd is aid 3, got: $line")
        assertTrue(line.contains("mid=m1"))
    }

    @Test
    fun `play stream puts the url last even when given first`() = runBlocking {
        server.onSuccess("browse/play_stream")
        connection.connect()

        client.playStream(pid = "1", url = "http://10.0.0.5:8080/a.flac?x=1&y=2")

        val line = server.received.single()
        assertTrue(
            line.indexOf("url=") > line.indexOf("pid="),
            "url must trail every other attribute, got: $line",
        )
    }

    @Test
    fun `get play mode decodes repeat and shuffle`() = runBlocking {
        server.onSuccess("player/get_play_mode", message = "pid=1&repeat=on_all&shuffle=on")
        connection.connect()

        val mode = client.getPlayMode("1")

        assertEquals(RepeatMode.All, mode.repeat)
        assertEquals(true, mode.shuffle)
    }

    @Test
    fun `a failed command raises an exception carrying the error id`() = runBlocking {
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"fail",""" +
                    """"message":"eid=8&text=Invalid ID&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"}}""",
            )
        }
        connection.connect()

        val thrown = assertFailsWith<HeosCommandException> { client.browse(sid = "1024", cid = "gone") }

        assertEquals(8, thrown.error.errorId)
        assertTrue(thrown.error.isMissingEntity)
    }

    @Test
    fun `unsolicited events reach the event flow and do not satisfy a pending command`() = runBlocking {
        server.on("player/get_play_state") { line ->
            listOf(
                // An event arriving before the response is the ordering that breaks naive clients.
                """{"heos":{"command":"event/player_now_playing_progress","message":"pid=1&cur_pos=1000&duration=240000"}}""",
                """{"heos":{"command":"player/get_play_state","result":"success",""" +
                    """"message":"pid=1&state=play&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"}}""",
            )
        }
        connection.connect()

        // The event flow does not replay, so the collector has to be live before the command that
        // provokes the event is sent.
        val subscribed = CompletableDeferred<Unit>()
        val firstEvent = CompletableDeferred<HeosFrame>()
        val collector = launch(Dispatchers.IO) {
            connection.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { firstEvent.complete(it) }
        }
        subscribed.await()

        val state = client.getPlayState("1")

        assertEquals(PlayState.Play, state)
        val event = withTimeout(5_000) { firstEvent.await() }
        assertEquals(
            HeosEvent.NowPlayingProgress(pid = "1", positionMillis = 1_000, durationMillis = 240_000),
            HeosEvent.from(event),
        )
        collector.cancel()
    }

    @Test
    fun `concurrent commands are matched by sequence rather than arrival order`() = runBlocking {
        server.on("player/get_volume") { line ->
            val sequence = FakeHeosServer.sequenceArgOf(line)
            // Answer with a level derived from the sequence so a mismatch is detectable.
            listOf(
                """{"heos":{"command":"player/get_volume","result":"success",""" +
                    """"message":"pid=1&level=$sequence&${HeosProtocol.SEQUENCE}=$sequence"}}""",
            )
        }
        connection.connect()

        val first = client.getVolume("1")
        val second = client.getVolume("1")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first + 1, second, "each caller must receive the response to its own command")
    }
}
