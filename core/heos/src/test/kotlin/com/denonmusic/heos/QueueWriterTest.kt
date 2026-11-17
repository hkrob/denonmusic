package com.denonmusic.heos

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals

/**
 * Pins the order of commands [addAllToQueue] puts on the wire. The receiver behaviour that order
 * works around cannot be reproduced by a fake - see the function's own comment for the measurement -
 * so what is asserted here is only that the sequence chosen because of it stays the sequence sent.
 */
@Timeout(30)
class QueueWriterTest {

    private lateinit var server: FakeHeosServer
    private lateinit var connection: HeosConnection
    private lateinit var client: HeosClient

    @BeforeEach
    fun setUp() {
        server = FakeHeosServer()
        server.onSuccess("browse/add_to_queue")
        server.onSuccess("player/clear_queue")
        server.onSuccess("player/set_play_state")
        queueCounts(26)
        connection = HeosConnection(server.host, server.port, commandTimeoutMillis = 5_000)
        client = HeosClient(connection)
    }

    @AfterEach
    fun tearDown() {
        connection.shutdown()
        server.close()
    }

    /** Answers each `get_queue` with the next of [counts], repeating the last one for good. */
    private fun queueCounts(vararg counts: Int) {
        var asked = 0
        server.on("player/get_queue") { line ->
            val count = counts[minOf(asked++, counts.lastIndex)]
            listOf(
                """{"heos":{"command":"player/get_queue","result":"success","message":"pid=1&range=0,0&returned=1&count=$count""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},"payload":[]}""",
            )
        }
    }

    /** Each command reduced to its path plus the one argument that distinguishes it. */
    private fun sent(): List<String> = server.received.map { line ->
        val path = line.removePrefix("heos://").substringBefore('?')
        val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
        when (path) {
            "browse/add_to_queue" -> "add cid=${args["cid"]} aid=${args["aid"]}"
            "player/set_play_state" -> "state ${args["state"]}"
            else -> path
        }
    }

    @Test
    fun `a multi-part replace clears, appends every part, then plays`() = runBlocking {
        queueCounts(13, 13, 26, 26)
        connection.connect()
        val completed = mutableListOf<Int>()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "disc1", null), QueueTarget("1024", "disc2", null)),
            criteria = AddCriteria.ReplaceAndPlay,
            onQueued = { completed += it },
        )

        assertEquals(
            listOf("player/clear_queue", "add cid=disc1 aid=3", "add cid=disc2 aid=3", "state play"),
            sent().filterNot { it == "player/get_queue" },
        )
        assertEquals(listOf(1, 2), completed)
    }

    @Test
    fun `each add is allowed to land before the next command is sent`() = runBlocking {
        // After the first add the receiver is still ingesting (13, then all 26); after the second
        // it reports 52 straight away. Neither the second add nor the play may be sent until the
        // count has passed where it was and read the same twice.
        queueCounts(13, 26, 26, 52, 52)
        connection.connect()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "disc1", null), QueueTarget("1024", "disc2", null)),
            criteria = AddCriteria.ReplaceAndPlay,
        )

        assertEquals(
            listOf(
                "player/clear_queue",
                "add cid=disc1 aid=3", "player/get_queue", "player/get_queue", "player/get_queue",
                "add cid=disc2 aid=3", "player/get_queue", "player/get_queue",
                "state play",
            ),
            sent(),
        )
    }

    @Test
    fun `appending to a queue that already has items waits for growth past its starting size`() = runBlocking {
        // 40 already queued: a reading of 40 twice must not be mistaken for the add having landed.
        queueCounts(40, 40, 40, 41, 41, 42, 42)
        connection.connect()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "a", "t1"), QueueTarget("1024", "a", "t2")),
            criteria = AddCriteria.AddToEnd,
        )

        val commands = sent()
        assertEquals("player/get_queue", commands.first(), "the starting size is read before anything is added")
        assertEquals(7, commands.count { it == "player/get_queue" })
        assertEquals(listOf("add cid=a aid=3", "add cid=a aid=3"), commands.filter { it.startsWith("add") })
    }

    @Test
    fun `a single add is not paced at all`() = runBlocking {
        connection.connect()

        client.addAllToQueue("1", listOf(QueueTarget("1024", "album", null)), AddCriteria.AddToEnd)

        assertEquals(listOf("add cid=album aid=3"), sent())
    }

    @Test
    fun `a receiver that will not report its queue is still told to play`() = runBlocking {
        server.on("player/get_queue") { line ->
            listOf(
                """{"heos":{"command":"player/get_queue","result":"fail","message":"eid=2&text=ID Not Valid""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"}}""",
            )
        }
        connection.connect()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "disc1", null), QueueTarget("1024", "disc2", null)),
            criteria = AddCriteria.ReplaceAndPlay,
        )

        assertEquals("state play", sent().last())
    }

    @Test
    fun `a single-part replace is sent as aid 4 alone`() = runBlocking {
        connection.connect()

        client.addAllToQueue("1", listOf(QueueTarget("1024", "album", null)), AddCriteria.ReplaceAndPlay)

        assertEquals(listOf("add cid=album aid=4"), sent())
    }

    @Test
    fun `only the first part carries a non-replacing criteria`() = runBlocking {
        queueCounts(26, 27, 27, 28, 28)
        connection.connect()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "a", null), QueueTarget("1024", "b", null)),
            criteria = AddCriteria.PlayNext,
        )

        assertEquals(listOf("add cid=a aid=2", "add cid=b aid=3"), sent().filterNot { it == "player/get_queue" })
    }
}
