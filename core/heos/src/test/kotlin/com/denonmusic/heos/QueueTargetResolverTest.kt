package com.denonmusic.heos

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [QueueTargetResolver] against [FakeHeosServer] over a real loopback socket, with a stubbed
 * library tree keyed by `cid`. Uses [runBlocking] rather than `runTest` for the same reason
 * [HeosClientTest] does: the client's command timeout is real wall-clock time.
 */
@Timeout(30)
class QueueTargetResolverTest {

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

    /** One level of a stubbed library: what a `browse` of a given cid answers with. */
    private data class Level(val items: List<String>, val playableContainer: Boolean = false)

    private fun track(name: String, mid: String) =
        """{"container":"no","playable":"yes","type":"song","name":"$name","mid":"$mid"}"""

    /** A sub-container of the *same* source - no `sid`, which is what marks it as one to recurse into. */
    private fun folder(name: String, cid: String) =
        """{"container":"yes","playable":"yes","type":"container","name":"$name","cid":"$cid"}"""

    /** Serves [tree] keyed by the `cid` argument of each incoming browse; unknown cids answer empty. */
    private fun serveTree(tree: Map<String?, Level>) {
        server.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            val level = tree[args["cid"]] ?: Level(emptyList())
            // Option id 21 is HEOS's "Play All", i.e. the playable-container flag.
            val options = if (level.playableContainer) {
                ""","options":[{"browse":[{"id":21,"name":"Play All"}]}]"""
            } else {
                ""
            }
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=${level.items.size}&count=${level.items.size}""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${level.items.joinToString(",")}]$options}""",
            )
        }
    }

    @Test
    fun `collects a level's own tracks even when it also has subfolders`() = runBlocking {
        // The regression this whole object exists to hold in one place: an ordinary album folder can
        // hold its tracks *and* incidental non-audio subfolders ("art"/"tech" extras). Recursing only
        // into the subfolders dropped every track at this level - "Nothing playable found" on a
        // folder with tracks plainly visible in the listing.
        serveTree(
            mapOf(
                "album" to Level(listOf(track("One", "m1"), track("Two", "m2"), folder("art", "art"))),
                "art" to Level(emptyList()),
            ),
        )
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album")

        assertEquals(listOf("m1", "m2"), targets.map { it.mid })
    }

    @Test
    fun `walks a multi-disc album whose own level is not a playable container`() = runBlocking {
        // No tracks and no playable flag at the album's own level, so a plain addToQueue(cid=album)
        // would silently do nothing - each disc has to be resolved separately.
        serveTree(
            mapOf(
                "album" to Level(listOf(folder("CD1", "cd1"), folder("CD2", "cd2"))),
                "cd1" to Level(listOf(track("A", "a1")), playableContainer = true),
                "cd2" to Level(listOf(track("B", "b1")), playableContainer = true),
            ),
        )
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album")

        // Each disc resolves to one whole-container add, not to its individual tracks.
        assertEquals(listOf("cd1", "cd2"), targets.map { it.cid })
        assertTrue(targets.all { it.mid == null }, "a playable container queues by cid, got $targets")
    }

    @Test
    fun `a playable container resolves to a single whole-container add`() = runBlocking {
        serveTree(mapOf("album" to Level(listOf(track("One", "m1"), track("Two", "m2")), playableContainer = true)))
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album")

        assertEquals(listOf(QueueTarget("1024", "album", null)), targets)
    }

    @Test
    fun `falls back to individual tracks when the container is not marked playable`() = runBlocking {
        serveTree(mapOf("album" to Level(listOf(track("One", "m1"), track("Two", "m2")))))
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album")

        assertEquals(listOf("m1", "m2"), targets.map { it.mid })
    }

    @Test
    fun `stops descending at maxDepth instead of following an unbounded chain`() = runBlocking {
        // Each level contains only a folder pointing one level deeper, forever.
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=1&count=1""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${folder("deeper", "deeper")}]}""",
            )
        }
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "top", maxDepth = 3)

        assertEquals(emptyList(), targets)
        // depth 0 (top) plus one browse per level down to the cutoff - bounded, not runaway.
        assertTrue(
            server.received.count { it.startsWith("heos://browse/browse") } <= 5,
            "expected the walk to stop at maxDepth, got ${server.received.size} browses",
        )
    }

    @Test
    fun `stops gathering once maxTargets is crossed`() = runBlocking {
        val discs = (1..10).map { folder("CD$it", "cd$it") }
        val tree = mutableMapOf<String?, Level>("album" to Level(discs))
        (1..10).forEach { disc ->
            tree["cd$disc"] = Level((1..5).map { track("T$it", "d${disc}t$it") })
        }
        serveTree(tree)
        connection.connect()

        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album", maxTargets = 12)

        // Overshoot is expected - the walk finishes the container that crosses the line rather than
        // truncating mid-folder - but it must not go on to gather all 50.
        assertTrue(targets.size in 13..20, "expected to stop shortly after 12, got ${targets.size}")
    }

    @Test
    fun `reports progress as the walk gathers targets`() = runBlocking {
        serveTree(
            mapOf(
                "album" to Level(listOf(folder("CD1", "cd1"), folder("CD2", "cd2"))),
                "cd1" to Level(listOf(track("A", "a1"), track("A2", "a2"))),
                "cd2" to Level(listOf(track("B", "b1"))),
            ),
        )
        connection.connect()

        val seen = mutableListOf<Int>()
        val targets = QueueTargetResolver.collect(client, sid = "1024", cid = "album") { seen += it }

        assertEquals(3, targets.size)
        assertTrue(seen.isNotEmpty(), "onProgress was never called")
        assertEquals(targets.size, seen.last(), "the final progress report should match the result size")
    }
}
