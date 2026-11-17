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
        val album = index.search("the wall").first().entry
        assertEquals(SearchRanking.Kind.Container, album.kind)
        assertEquals("wall", album.cid, "a container is addressed by its own cid, so it can be walked")
        val song = index.search("comfortably numb").first().entry
        assertEquals("wall", song.cid, "a track is addressed by its enclosing folder")
        assertEquals("t1", song.mid)
    }

    @Test
    fun `a folder can be found by naming the folder above it`() {
        fake.serveTree(tree)
        val index = index()

        index.rebuildAndWait()

        assertEquals("The Wall", index.search("wall", artist = "pink floyd").first().entry.name)
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
