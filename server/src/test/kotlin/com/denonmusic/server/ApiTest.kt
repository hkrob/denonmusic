package com.denonmusic.server

import com.denonmusic.server.FakeReceiver.Companion.TOKEN
import com.denonmusic.server.FakeReceiver.Companion.track
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Socket
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HTTP routes end to end: a real Ktor pipeline in front, the real sessions and protocol clients
 * in the middle, and [FakeReceiver] on loopback sockets behind. What is asserted is what reached
 * the wire - a fake cannot say how a receiver would have *behaved*.
 */
@Timeout(60)
class ApiTest {

    @TempDir
    lateinit var dir: File

    private lateinit var fake: FakeReceiver
    private lateinit var receiver: Receiver
    private lateinit var scope: CoroutineScope
    private lateinit var config: Config
    private lateinit var index: LibraryIndex

    @BeforeEach
    fun setUp() {
        fake = FakeReceiver()
        fake.heos.onSuccess("player/set_play_state")
        fake.heos.onSuccess("player/set_volume")
        fake.heos.onSuccess("player/clear_queue")
        fake.heos.onSuccess("browse/add_to_queue")
        fake.heos.onSuccess("player/get_volume", message = "pid=7&level=30")
        config = fake.config(dir)
        receiver = Receiver(config)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        index = LibraryIndex(File(dir, "index.json"), scope, { receiver.heos.heosClient }, receiver::sourceSid)
        receiver.start()
        awaitTrue("the HEOS session") { receiver.heos.heosClient != null }
        awaitTrue("the AVR session") { receiver.avr.avrClient != null }
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        receiver.stop()
        fake.close()
    }

    private fun api(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(config, Controller(config, receiver, index, scope, firstAddSettleMillis = 50)) }
        test()
    }

    private suspend fun ApplicationTestBuilder.authedGet(path: String) = client.get(path) { header("X-Lan-Control-Token", TOKEN) }

    private suspend fun ApplicationTestBuilder.authedPost(path: String, body: String? = null) = client.post(path) {
        header("X-Lan-Control-Token", TOKEN)
        body?.let { setBody(it) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun buildIndex(tree: Map<String?, List<String>>) {
        fake.serveTree(tree)
        assertTrue(index.startRebuild())
        awaitTrue("the crawl to finish") { index.status.state != "indexing" }
        assertEquals("ready", index.status.state)
        fake.heos.received.clear()
    }

    // -- auth ----------------------------------------------------------------

    @Test
    fun `a request without the token is refused before anything reaches the receiver`() = api {
        val before = fake.sent().size

        assertEquals(HttpStatusCode.Unauthorized, client.post("/play").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/play") { header("X-Lan-Control-Token", "wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/play?token=$TOKEN").status)

        assertEquals(before, fake.sent().size)
    }

    @Test
    fun `a bearer token is accepted as well as the app's header`() = api {
        assertEquals(HttpStatusCode.OK, client.post("/play") { header("Authorization", "Bearer $TOKEN") }.status)
    }

    @Test
    fun `health needs no token`() = api {
        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Connected", response.json()["heos"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the root page needs no token and shows only connection and index state`() = api {
        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val page = response.bodyAsText()
        assertContains(page, "<th>HEOS (port 1255)</th><td><span class=\"dot ok\"></span>Connected</td>")
        assertContains(page, "none scheduled")
        assertFalse(TOKEN in page, "the page must not echo the token")
    }

    @Test
    fun `a line ending in a parameter never reaches the receiver`() = api {
        val response = authedPost("/queue/add?sid=1024&cid=a%0D%0Aheos://player/clear_queue?pid=7&criteria=AddToEnd")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(fake.sent().any { it.startsWith("browse/add_to_queue") || it.startsWith("player/clear_queue") })
    }

    /**
     * Over a real socket, because Ktor's own client refuses to build this URL at all - which is
     * exactly why the test host could never have found what it guards against.
     */
    @Test
    fun `a malformed percent escape is a bad request, with or without the token`() {
        val controller = Controller(config, receiver, index, scope, firstAddSettleMillis = 50)
        val server = embeddedServer(CIO, port = 0) { api(config, controller) }.start()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }

            fun statusOf(path: String, token: String? = null): String = Socket("127.0.0.1", port).use { socket ->
                val tokenHeader = token?.let { "X-Lan-Control-Token: $it\r\n" }.orEmpty()
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n${tokenHeader}Connection: close\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readLine().orEmpty()
            }

            assertContains(statusOf("/search?q=%zz"), " 400 ")
            assertContains(statusOf("/search?q=%zz", TOKEN), " 400 ")
            assertContains(statusOf("/health"), " 200 ", message = "and the server is still answering")
        } finally {
            server.stop(100, 500)
        }
    }

    // -- transport and volume --------------------------------------------------

    @Test
    fun `transport routes address the player the receiver reports`() = api {
        assertEquals(HttpStatusCode.OK, authedPost("/pause").status)

        assertContains(fake.sent("pid", "state"), "player/set_play_state pid=7 state=pause")
    }

    @Test
    fun `a volume above the ceiling is clamped and the applied level reported`() = api {
        val response = authedPost("/volume?level=95")

        assertEquals(60, response.json()["volume"]!!.jsonPrimitive.content.toInt())
        assertContains(fake.sent("level"), "player/set_volume level=60")
    }

    @Test
    fun `a volume delta moves from the level the receiver reports`() = api {
        authedPost("/volume?delta=-5")

        assertContains(fake.sent("level"), "player/set_volume level=25")
    }

    @Test
    fun `turning up a volume already above the ceiling does not turn it down`() = api {
        fake.heos.onSuccess("player/get_volume", message = "pid=7&level=80")

        val response = authedPost("/volume?delta=5")

        assertEquals(80, response.json()["volume"]!!.jsonPrimitive.content.toInt())
        assertFalse(fake.sent().any { it.startsWith("player/set_volume") })
    }

    @Test
    fun `the queue is read to its end, not just its first page`() = api {
        fake.heos.on("player/get_queue") { line ->
            val range = com.denonmusic.heos.HeosProtocol.parseMessage(line.substringAfter('?', ""))["range"]!!
            val (start, end) = range.split(',').map { it.toInt() }
            val items = (start..minOf(end, 119)).joinToString(",") {
                """{"song":"s$it","album":"a","artist":"b","qid":${it + 1},"mid":"m$it"}"""
            }
            listOf(
                """{"heos":{"command":"player/get_queue","result":"success","message":"pid=7&range=$range""" +
                    """&SEQUENCE=${com.denonmusic.heos.FakeHeosServer.sequenceArgOf(line)}"},"payload":[$items]}""",
            )
        }

        val queue = Json.parseToJsonElement(authedGet("/queue").bodyAsText()).jsonArray

        assertEquals(120, queue.size)
        assertEquals(120, queue.last().jsonObject["qid"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `power goes to the telnet port`() = api {
        assertEquals(HttpStatusCode.OK, authedPost("/power?on=false").status)

        awaitTrue("PWSTANDBY to arrive") { "PWSTANDBY" in fake.avr.received }
    }

    @Test
    fun `status reports what the receiver answers and tolerates what it does not`() = api {
        fake.heos.onSuccess("player/get_play_state", message = "pid=7&state=play")
        fake.heos.onSuccess(
            "player/get_now_playing_media",
            payload = """{"type":"song","song":"Money","album":"The Dark Side of the Moon","artist":"Pink Floyd","mid":"m","qid":"1"}""",
        )
        fake.avr.onLines("PW?", "PWON")
        // SI?, MS? and MU? get no answer at all, as from a unit that is not listening.

        val status = authedGet("/status").json()

        assertEquals("Money", status["song"]!!.jsonPrimitive.content)
        assertEquals("play", status["playState"]!!.jsonPrimitive.content)
        assertEquals(30, status["volume"]!!.jsonPrimitive.content.toInt())
        assertEquals("On", status["avrPower"]!!.jsonPrimitive.content)
        assertEquals("null", status["input"].toString())
    }

    // -- search and intent -----------------------------------------------------

    private val library = mapOf(
        null to listOf(FakeReceiver.folder("Pink Floyd", "pf")),
        "pf" to listOf(FakeReceiver.folder("The Wall", "wall")),
        "wall" to listOf(
            FakeReceiver.folder("CD1", "cd1"),
            FakeReceiver.folder("CD2", "cd2"),
        ),
        "cd1" to listOf(track("In the Flesh", "t1", "Pink Floyd", "The Wall")),
        "cd2" to listOf(track("Comfortably Numb", "t2", "Pink Floyd", "The Wall")),
    )

    @Test
    fun `search returns ranked hits with the ids to queue them`() = api {
        buildIndex(library)

        val results = authedGet("/search?q=comfortably+numb").json()["results"]!!.jsonArray

        val hit = results.first().jsonObject
        assertEquals("Comfortably Numb", hit["name"]!!.jsonPrimitive.content)
        assertEquals("t2", hit["mid"]!!.jsonPrimitive.content)
        assertEquals("cd2", hit["cid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `playing a track replaces the queue with aid 4`() = api {
        buildIndex(library)

        val response = authedPost("/intent", """{"action":"play","query":"comfortably numb"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Playing Comfortably Numb by Pink Floyd", response.json()["speech"]!!.jsonPrimitive.content)
        assertEquals(listOf("browse/add_to_queue cid=cd2 mid=t2 aid=4"), fake.sent("cid", "mid", "aid"))
    }

    @Test
    fun `playing a multi-folder album starts the first folder alone and appends the rest behind it`() = api {
        buildIndex(library)

        val response = authedPost("/intent", """{"action":"play","query":"the wall","type":"album"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        awaitTrue("the second folder to be appended") { fake.sent("cid", "aid").any { it == "browse/add_to_queue cid=cd2 aid=3" } }
        // aid=4 goes alone - never as one of a run, which is the form the receiver drops.
        assertEquals(
            listOf("browse/add_to_queue cid=cd1 aid=4", "browse/add_to_queue cid=cd2 aid=3"),
            fake.sent("cid", "aid").filter { it.startsWith("browse/add_to_queue") },
        )
        assertFalse(fake.sent().any { it.startsWith("browse/browse") }, "an album's folders are queued as they are, not walked")
    }

    @Test
    fun `a new request stops the previous one's appends`() = api {
        val folders = (1..20).map { "f$it" }
        buildIndex(
            mapOf<String?, List<String>>(null to folders.map { FakeReceiver.folder("Album $it", it) }) +
                folders.associateWith { listOf(track("Song $it", "m$it", "Prolific", "Album $it")) },
        )

        authedPost("/intent", """{"action":"play","query":"prolific","type":"artist"}""")
        authedPost("/intent", """{"action":"play","query":"song f3","type":"track"}""")
        val afterSecond = fake.sent("mid", "aid").indexOfLast { it == "browse/add_to_queue mid=mf3 aid=4" }
        Thread.sleep(600)

        assertTrue(afterSecond >= 0)
        assertEquals(
            emptyList(),
            fake.sent("mid", "aid").drop(afterSecond + 1).filter { it.startsWith("browse/add_to_queue") },
            "nothing from the first request may be added after the second replaced the queue",
        )
    }

    @Test
    fun `an explicit container add walks it and uses the clear, append, play sequence`() = api {
        buildIndex(library)

        val response = authedPost("/queue/add?sid=1024&cid=wall&criteria=ReplaceAndPlay")

        assertEquals(HttpStatusCode.OK, response.status)
        val sent = fake.sent("cid", "mid", "aid", "state").filterNot { it.startsWith("browse/browse") }
        assertEquals("player/clear_queue", sent.first())
        assertEquals("player/set_play_state state=play", sent.last())
        val adds = sent.filter { it.startsWith("browse/add_to_queue") }
        assertEquals(2, adds.size)
        assertTrue(adds.all { it.endsWith("aid=3") }, "a multi-part replace must never send aid=4: $adds")
    }

    @Test
    fun `an artist with more folders than one request queues is cut short and says so`() = api {
        val folders = (1..30).map { "f$it" }
        buildIndex(
            mapOf<String?, List<String>>(null to folders.map { FakeReceiver.folder("Album $it", it) }) +
                folders.associateWith { listOf(track("Song $it", "m$it", "Prolific", "Album $it")) },
        )

        val reply = authedPost("/intent", """{"action":"play","query":"prolific","type":"artist"}""").json()

        assertEquals("Playing Prolific, the first 25 of 30 albums", reply["speech"]!!.jsonPrimitive.content)
        awaitTrue("all 25 folders to be queued") { fake.sent("aid").count { it.startsWith("browse/add_to_queue") } == 25 }
        Thread.sleep(300)
        assertEquals(25, fake.sent("aid").count { it.startsWith("browse/add_to_queue") })
    }

    @Test
    fun `queue appends rather than replaces`() = api {
        buildIndex(library)

        authedPost("/intent", """{"action":"queue","query":"in the flesh"}""")

        assertEquals(listOf("browse/add_to_queue cid=cd1 mid=t1 aid=3"), fake.sent("cid", "mid", "aid"))
    }

    @Test
    fun `an intent that matches nothing says so and queues nothing`() = api {
        buildIndex(library)

        val response = authedPost("/intent", """{"action":"play","query":"led zeppelin"}""")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(fake.sent().isEmpty())
    }

    @Test
    fun `play before any index exists explains what is missing`() = api {
        val response = authedPost("/intent", """{"action":"play","query":"the wall"}""")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertContains(response.json()["error"]!!.jsonPrimitive.content, "/index/rebuild")
    }

    @Test
    fun `control intents need no index`() = api {
        assertEquals(HttpStatusCode.OK, authedPost("/intent", """{"action":"pause"}""").status)
        assertEquals(HttpStatusCode.OK, authedPost("/intent", """{"action":"volume_down"}""").status)
        assertEquals(HttpStatusCode.OK, authedPost("/intent", """{"action":"set_volume","query":"20"}""").status)

        assertEquals(
            listOf("player/set_play_state state=pause", "player/get_volume", "player/set_volume level=25", "player/set_volume level=20"),
            fake.sent("state", "level"),
        )
    }

    @Test
    fun `a malformed or unknown intent is the caller's error`() = api {
        assertEquals(HttpStatusCode.BadRequest, authedPost("/intent", "not json").status)
        assertEquals(HttpStatusCode.BadRequest, authedPost("/intent", """{"action":"explode"}""").status)
        assertEquals(HttpStatusCode.BadRequest, authedPost("/intent", """{"action":"play_next"}""").status)
    }

    @Test
    fun `a receiver refusal is reported as the receiver's, not the server's`() = api {
        fake.heos.on("player/play_next") { line ->
            listOf(
                """{"heos":{"command":"player/play_next","result":"fail","message":"eid=2&text=ID Not Valid""" +
                    """&SEQUENCE=${com.denonmusic.heos.FakeHeosServer.sequenceArgOf(line)}"}}""",
            )
        }

        assertEquals(HttpStatusCode.BadGateway, authedPost("/next").status)
    }
}
