package com.denonmusic.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.denonmusic.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SearchIndexDao.findTrack] is the join point between a Plex playlist item and this app's own HEOS
 * queue target (see the app's `PlaylistRepository`) - these pin what counts as a match.
 */
@ExtendWith(RobolectricExtension::class)
class SearchIndexDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SearchIndexDao

    private fun track(name: String, artist: String?, album: String?, queueCid: String = "cid-$name") =
        SearchIndexEntity(
            name = name,
            nameLower = name.lowercase(),
            artist = artist,
            album = album,
            isContainer = false,
            isTrack = true,
            pathJson = "",
            queueSid = "1024",
            queueCid = queueCid,
            mid = null,
            indexedAt = 1L,
        )

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.searchIndexDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `matches on title alone, case-insensitively`() = runBlocking {
        dao.insertAll(listOf(track("Rolling in the Deep", artist = "Adele", album = "21")))

        val hit = dao.findTrack("rolling in the deep", artist = null, album = null)

        assertEquals(1, hit.size)
        assertEquals("cid-Rolling in the Deep", hit.first().queueCid)
    }

    @Test
    fun `artist and album narrow the match when more than one track shares a title`() = runBlocking {
        dao.insertAll(
            listOf(
                track("Home", artist = "Adele", album = "Live", queueCid = "adele-home"),
                track("Home", artist = "Michael Buble", album = "Christmas", queueCid = "buble-home"),
            ),
        )

        val hit = dao.findTrack("home", artist = "Michael Buble", album = "Christmas")

        assertEquals(listOf("buble-home"), hit.map { it.queueCid })
    }

    @Test
    fun `a container row never matches - only isTrack rows are candidates`() = runBlocking {
        dao.insertAll(
            listOf(
                SearchIndexEntity(
                    name = "21",
                    nameLower = "21",
                    artist = null,
                    album = null,
                    isContainer = true,
                    isTrack = false,
                    pathJson = "[]",
                    queueSid = "1024",
                    queueCid = "album-cid",
                    mid = null,
                    indexedAt = 1L,
                ),
            ),
        )

        assertTrue(dao.findTrack("21", artist = null, album = null).isEmpty())
    }

    @Test
    fun `no match returns empty, not an exception`() = runBlocking {
        assertTrue(dao.findTrack("nothing here", artist = null, album = null).isEmpty())
    }
}
