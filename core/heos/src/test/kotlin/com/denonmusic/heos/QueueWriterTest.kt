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
        connection = HeosConnection(server.host, server.port, commandTimeoutMillis = 5_000)
        client = HeosClient(connection)
    }

    @AfterEach
    fun tearDown() {
        connection.shutdown()
        server.close()
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
            sent(),
        )
        assertEquals(listOf(1, 2), completed)
    }

    @Test
    fun `a single-part replace is sent as aid 4 alone`() = runBlocking {
        connection.connect()

        client.addAllToQueue("1", listOf(QueueTarget("1024", "album", null)), AddCriteria.ReplaceAndPlay)

        assertEquals(listOf("add cid=album aid=4"), sent())
    }

    @Test
    fun `only the first part carries a non-replacing criteria`() = runBlocking {
        connection.connect()

        client.addAllToQueue(
            pid = "1",
            targets = listOf(QueueTarget("1024", "a", null), QueueTarget("1024", "b", null)),
            criteria = AddCriteria.PlayNext,
        )

        assertEquals(listOf("add cid=a aid=2", "add cid=b aid=3"), sent())
    }
}
