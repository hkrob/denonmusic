package com.denonmusic.server

import com.denonmusic.server.FakeReceiver.Companion.folder
import com.denonmusic.server.FakeReceiver.Companion.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(30)
class LibraryIndexTest {

    @TempDir
    lateinit var dir: File

    private lateinit var fake: FakeReceiver
    private lateinit var receiver: Receiver
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        fake = FakeReceiver()
        receiver = Receiver(fake.config(dir))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiver.start()
        awaitTrue("the HEOS session") { receiver.heos.heosClient != null }
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        receiver.stop()
        fake.close()
    }

    private fun index(file: File = File(dir, "index.json"), connected: Boolean = true) = LibraryIndex(
        file = file,
        scope = scope,
        clientProvider = { if (connected) receiver.heos.heosClient else null },
        sourceSid = receiver::sourceSid,
        retryDelayMillis = 10,
    )

    private fun LibraryIndex.rebuildAndWait() {
        assertTrue(startRebuild())
        awaitTrue("the crawl to finish") { status.state != "indexing" }
    }

    private val tree = mapOf(
        null to listOf(folder("Pink Floyd", "pf")),
        "pf" to listOf(folder("The Wall", "wall")),
        "wall" to listOf(track("Comfortably Numb", "t1", artist = "Pink Floyd", album = "The Wall")),
    )

    @Test
    fun `a crawl makes folders and tracks searchable with the ids to queue them`() {
        fake.serveTree(tree)
        val index = index()

        index.rebuildAndWait()

        assertEquals("ready", index.status.state)
        assertEquals(3, index.status.count)
        val folder = index.search("the wall", kind = SearchRanking.Kind.Container).first().entry
        assertEquals("wall", folder.cid, "a container is addressed by its own cid, so it can be walked")
        val song = index.search("comfortably numb").first().entry
        assertEquals("wall", song.cid, "a track is addressed by its enclosing folder")
        assertEquals("t1", song.mid)
    }

    @Test
    fun `a folder can be found by naming the folder above it`() {
        fake.serveTree(tree)
        val index = index()

        index.rebuildAndWait()

        val hit = index.search("wall", artist = "pink floyd", kind = SearchRanking.Kind.Container).first().entry
        assertEquals("The Wall", hit.name)
    }

    // -- albums and artists derived from tags ----------------------------------

    private fun taggedTrack(name: String, cid: String, artist: String, album: String) = IndexedEntry(
        name = name,
        artist = artist,
        album = album,
        kind = SearchRanking.Kind.Track,
        sid = "1024",
        cid = cid,
        mid = "$cid-$name",
    )

    @Test
    fun `an album spread over sibling disc folders is one entry listing them in order`() {
        // The shape that had no answer on the real library: no folder is called "The Wall".
        val derived = LibraryIndex.derive(
            listOf(
                taggedTrack("In the Flesh", "disc1", "Pink Floyd", "The Wall"),
                taggedTrack("Hey You", "disc2", "Pink Floyd", "The Wall"),
                taggedTrack("Money", "dsotm", "Pink Floyd", "Dark Side of the Moon"),
            ),
        )

        val wall = derived.single { it.kind == SearchRanking.Kind.Album && it.name == "The Wall" }
        assertEquals(listOf("disc1", "disc2"), wall.folders.map { it.cid })
        assertEquals("Pink Floyd", wall.artist)
        val artist = derived.single { it.kind == SearchRanking.Kind.Artist }
        assertEquals(listOf("disc1", "disc2", "dsotm"), artist.folders.map { it.cid })
    }

    @Test
    fun `the same album filed in two places is listed once`() {
        val derived = LibraryIndex.derive(
            listOf(
                taggedTrack("The Wheel", "indie", "PJ Harvey", "The Hope Six Demolition Project"),
                taggedTrack("The Wheel", "new", "PJ Harvey", "The Hope Six Demolition Project"),
            ),
        )

        assertEquals(listOf("indie"), derived.single { it.kind == SearchRanking.Kind.Album }.folders.map { it.cid })
        assertEquals(listOf("indie"), derived.single { it.kind == SearchRanking.Kind.Artist }.folders.map { it.cid })
    }

    @Test
    fun `two artists' albums of the same name stay apart`() {
        val derived = LibraryIndex.derive(
            listOf(taggedTrack("a", "x", "Weezer", "Greatest Hits"), taggedTrack("b", "y", "Queen", "Greatest Hits")),
        )

        assertEquals(2, derived.count { it.kind == SearchRanking.Kind.Album })
    }

    @Test
    fun `derived entries are searchable but not what is counted or saved`() {
        fake.serveTree(tree)
        val file = File(dir, "index.json")
        val index = index(file)

        index.rebuildAndWait()

        assertEquals(SearchRanking.Kind.Album, index.search("the wall").first().entry.kind)
        assertEquals(SearchRanking.Kind.Artist, index.search("pink floyd").first().entry.kind)
        assertEquals(3, index.status.count)
        assertFalse(file.readText().contains("\"Album\""))
    }

    @Test
    fun `a saved index is searchable after a restart without another crawl`() {
        fake.serveTree(tree)
        index().rebuildAndWait()
        val browsesBefore = fake.sent().count { it == "browse/browse" }

        val restarted = index()
        restarted.load()

        assertEquals("ready", restarted.status.state)
        assertNotNull(restarted.search("comfortably numb").firstOrNull())
        assertEquals(browsesBefore, fake.sent().count { it == "browse/browse" })
    }

    @Test
    fun `a failed crawl leaves the previous index in place`() {
        fake.serveTree(tree)
        val file = File(dir, "index.json")
        index(file).rebuildAndWait()

        val disconnected = index(file, connected = false)
        disconnected.load()
        disconnected.rebuildAndWait()

        assertEquals(3, disconnected.status.count)
        assertEquals("Not connected to the receiver", disconnected.status.error)
        assertNotNull(disconnected.search("the wall").firstOrNull())
    }

    @Test
    fun `an unreadable index file is reported, not fatal`() {
        val file = File(dir, "index.json").apply { writeText("{not json") }
        val index = index(file)

        index.load()

        assertEquals("failed", index.status.state)
        assertNull(index.search("anything").firstOrNull())
    }

    @Test
    fun `a second rebuild is refused while one is running`() {
        // No browse responder at all, so the crawl sits waiting on its first folder.
        val index = index()

        assertTrue(index.startRebuild())
        assertFalse(index.startRebuild())
    }
}
