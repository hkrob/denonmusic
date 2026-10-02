package com.denonmusic.heos

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [LibraryIndexer] against [FakeHeosServer] over a real loopback socket - same harness as
 * [QueueTargetResolverTest], which this mirrors the shape of.
 */
@Timeout(30)
class LibraryIndexerTest {

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

    private data class Level(val items: List<String>)

    private fun track(name: String, mid: String) =
        """{"container":"no","playable":"yes","type":"song","name":"$name","mid":"$mid"}"""

    private fun folder(name: String, cid: String) =
        """{"container":"yes","playable":"yes","type":"container","name":"$name","cid":"$cid"}"""

    /** A row that switches to a different source entirely, e.g. a DLNA server under the aggregate. */
    private fun nestedSource(name: String, sid: String) =
        """{"container":"yes","playable":"yes","type":"container","name":"$name","sid":"$sid"}"""

    private fun serveTree(tree: Map<String?, Level>) {
        server.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            val level = tree[args["cid"]] ?: Level(emptyList())
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=${level.items.size}&count=${level.items.size}""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${level.items.joinToString(",")}]}""",
            )
        }
    }

    private suspend fun crawl(sid: String = "1024", name: String = "Library"): List<LibraryEntry> {
        val found = mutableListOf<LibraryEntry>()
        LibraryIndexer.crawl(client, sid, name) { found += it }
        return found
    }

    @Test
    fun `indexes both a folder and its tracks, not just the leaves`() = runBlocking {
        serveTree(
            mapOf(
                null to Level(listOf(folder("Album", "album"))),
                "album" to Level(listOf(track("One", "m1"), track("Two", "m2"))),
            ),
        )
        connection.connect()

        val entries = crawl()

        val containers = entries.filter { it.isContainer }
        val tracks = entries.filter { it.isTrack }
        assertEquals(listOf("Album"), containers.map { it.name })
        assertEquals(listOf("m1", "m2"), tracks.map { it.mid })
    }

    @Test
    fun `a container's path ends at itself, root first`() = runBlocking {
        serveTree(
            mapOf(
                null to Level(listOf(folder("Artist", "artist"))),
                "artist" to Level(listOf(folder("Album", "album"))),
                "album" to Level(emptyList()),
            ),
        )
        connection.connect()

        val entries = crawl(name = "Library")

        val album = entries.first { it.name == "Album" }
        assertEquals(
            listOf("1024" to null, "1024" to "artist", "1024" to "album"),
            album.path.map { it.sid to it.cid },
        )
        assertEquals(listOf("Library", "Artist", "Album"), album.path.map { it.name })
    }

    @Test
    fun `a track carries no path - it is queued, not navigated to`() = runBlocking {
        serveTree(mapOf(null to Level(listOf(track("Solo", "m1")))))
        connection.connect()

        val entries = crawl()

        assertEquals(emptyList(), entries.single().path)
    }

    @Test
    fun `a track's queue target falls back to its parent cid when it has none of its own`() = runBlocking {
        serveTree(
            mapOf(
                null to Level(listOf(folder("Album", "album"))),
                "album" to Level(listOf(track("One", "m1"))),
            ),
        )
        connection.connect()

        val entries = crawl().filter { it.isTrack }

        assertEquals("1024", entries.single().queueSid)
        assertEquals("album", entries.single().queueCid)
    }

    @Test
    fun `descends into a nested source by switching sid, not appending its cid`() = runBlocking {
        server.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            val payload = when (args["sid"]) {
                "1024" -> listOf(nestedSource("Plex Media Server", "-5"))
                "-5" -> listOf(track("Song", "m1"))
                else -> emptyList()
            }
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=${args["sid"]}&returned=${payload.size}&count=${payload.size}""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${payload.joinToString(",")}]}""",
            )
        }
        connection.connect()

        val entries = crawl()

        val nested = entries.first { it.name == "Plex Media Server" }
        assertEquals("-5" to null, nested.path.last().let { it.sid to it.cid })
        val track = entries.first { it.isTrack }
        assertEquals("-5", track.queueSid)
    }

    @Test
    fun `one unreadable folder does not abort the rest of the walk`() = runBlocking {
        server.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            when (args["cid"]) {
                null -> listOf(
                    """{"heos":{"command":"browse/browse","result":"success",""" +
                        """"message":"sid=1024&returned=2&count=2""" +
                        """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                        """"payload":[${folder("Broken", "broken")},${folder("Fine", "fine")}]}""",
                )
                "broken" -> listOf(
                    """{"heos":{"command":"browse/browse","result":"fail",""" +
                        """"message":"eid=12&text=System error""" +
                        """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"}}""",
                )
                "fine" -> listOf(
                    """{"heos":{"command":"browse/browse","result":"success",""" +
                        """"message":"sid=1024&returned=1&count=1""" +
                        """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                        """"payload":[${track("Survivor", "m1")}]}""",
                )
                else -> listOf(
                    """{"heos":{"command":"browse/browse","result":"success",""" +
                        """"message":"sid=1024&returned=0&count=0""" +
                        """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                        """"payload":[]}""",
                )
            }
        }
        connection.connect()

        val entries = crawl()

        assertTrue(entries.any { it.name == "Survivor" }, "the sibling of a broken folder should still be indexed")
    }

    @Test
    fun `a dropped connection aborts the walk instead of finishing with a truncated result`() = runBlocking {
        // Unlike a receiver-reported "fail" result (the test above), a transport failure is not safe
        // to read as "this folder is empty" - the whole point of this test is that it must not be.
        server.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            if (args["cid"] == null) {
                listOf(
                    """{"heos":{"command":"browse/browse","result":"success",""" +
                        """"message":"sid=1024&returned=1&count=1""" +
                        """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                        """"payload":[${folder("Album", "album")}]}""",
                )
            } else {
                server.close()
                emptyList()
            }
        }
        connection.connect()

        val result = runCatching { LibraryIndexer.crawl(client, "1024", "Library") { } }

        assertTrue(result.isFailure, "a dropped connection should fail the whole walk, not finish it silently")
        assertTrue(
            result.exceptionOrNull() !is HeosCommandException,
            "a transport failure is not the kind of per-folder error the walk is allowed to skip",
        )
    }

    @Test
    fun `stops descending at maxDepth instead of following an unbounded chain`() = runBlocking {
        server.on("browse/browse") { line ->
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=1&count=1""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${folder("deeper", "deeper")}]}""",
            )
        }
        connection.connect()

        val found = mutableListOf<LibraryEntry>()
        LibraryIndexer.crawl(client, "1024", "Library", maxDepth = 3) { found += it }

        assertEquals(4, found.size, "depth 0 through 3 should each contribute one 'deeper' folder")
        assertTrue(
            server.received.count { it.startsWith("heos://browse/browse") } <= 5,
            "expected the walk to stop at maxDepth, got ${server.received.size} browses",
        )
    }

    @Test
    fun `never walks into Plex's own Video or Photos libraries by name`() = runBlocking {
        // Measured against a real Plex-backed AVR-X4500H: a walk that descends into Video before
        // Music burns its whole run on actor/movie folders and finds zero tracks - see the class doc.
        serveTree(
            mapOf(
                null to Level(listOf(folder("Video", "video"), folder("Music", "music"))),
                "video" to Level(listOf(folder("By Starring Actor", "actors"))),
                "actors" to Level(listOf(folder("Some Actor", "actor1"))),
                "music" to Level(listOf(track("Song", "m1"))),
            ),
        )
        connection.connect()

        val entries = crawl()

        assertEquals(listOf("Song"), entries.filter { it.isTrack }.map { it.name })
        assertTrue(
            entries.none { it.name == "Video" || it.name == "By Starring Actor" || it.name == "Some Actor" },
            "nothing under Video should be indexed",
        )
        assertTrue(
            server.received.none { it.contains("cid=video") || it.contains("cid=actors") },
            "Video's subtree should never be browsed at all, not just excluded from the result",
        )
    }

    @Test
    fun `collapses several parallel library views into one walk, preferring By Folder`() = runBlocking {
        // Measured against the same receiver: its music library offers eight views of the identical
        // content (By Album, By Artist, By Genre, ...). Walking all of them would index every track
        // up to eight times over - see the class doc.
        serveTree(
            mapOf(
                null to Level(
                    listOf(folder("All Artists", "allartists"), folder("By Album", "byalbum"), folder("By Folder", "byfolder")),
                ),
                "allartists" to Level(listOf(track("DuplicateA", "dup1"))),
                "byalbum" to Level(listOf(track("DuplicateB", "dup1"))),
                "byfolder" to Level(listOf(track("Real", "m1"))),
            ),
        )
        connection.connect()

        val entries = crawl()

        assertEquals(listOf("Real"), entries.filter { it.isTrack }.map { it.name })
        assertTrue(
            entries.none { it.name in setOf("All Artists", "By Album", "By Folder") },
            "a view container is a navigation artifact, not something anyone searches for",
        )
        assertTrue(
            server.received.none { it.contains("cid=allartists") || it.contains("cid=byalbum") },
            "only the preferred view should ever be browsed, not walked and then discarded",
        )
    }

    @Test
    fun `falls back to the first recognised view when neither preferred name is present`() = runBlocking {
        serveTree(
            mapOf(
                null to Level(listOf(folder("By Genre", "bygenre"), folder("By Decade", "bydecade"))),
                "bygenre" to Level(listOf(track("FromGenre", "m1"))),
                "bydecade" to Level(listOf(track("FromDecade", "m2"))),
            ),
        )
        connection.connect()

        val entries = crawl()

        assertEquals(listOf("FromGenre"), entries.filter { it.isTrack }.map { it.name })
    }
}
