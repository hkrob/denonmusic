package com.denonmusic.data.browse

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
import kotlin.test.assertNull

@ExtendWith(RobolectricExtension::class)
class BrowseStackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BrowseStackDao

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.browseStackDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `replaceStack drops deeper rows left over from a longer previous stack`() = runBlocking {
        dao.replaceStack(
            listOf(
                BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root"),
                BrowseStackEntity(position = 1, sid = "1024", cid = "a", displayName = "A"),
                BrowseStackEntity(position = 2, sid = "1024", cid = "b", displayName = "B"),
            ),
        )

        // A shallower push replacing the whole stack, per the DAO's own @Transaction doc: this must
        // never leave "B" and "A" behind under the new, shorter breadcrumb.
        dao.replaceStack(listOf(BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root")))

        assertEquals(listOf("Root"), dao.getStack().map { it.displayName })
    }

    @Test
    fun `replaceStack with an empty list clears the whole stack`() = runBlocking {
        dao.replaceStack(listOf(BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root")))

        dao.replaceStack(emptyList())

        assertEquals(emptyList(), dao.getStack())
    }

    @Test
    fun `truncateAfter drops only rows beyond the kept position`() = runBlocking {
        dao.insertAll(
            listOf(
                BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root"),
                BrowseStackEntity(position = 1, sid = "1024", cid = "a", displayName = "A"),
                BrowseStackEntity(position = 2, sid = "1024", cid = "b", displayName = "B"),
            ),
        )

        dao.truncateAfter(0)

        assertEquals(listOf("Root"), dao.getStack().map { it.displayName })
    }

    @Test
    fun `updateScroll persists position for a later restore`() = runBlocking {
        dao.insertAll(listOf(BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root")))

        dao.updateScroll(position = 0, scrollIndex = 5, scrollOffset = 42)

        val row = dao.getStack().single()
        assertEquals(5, row.scrollIndex)
        assertEquals(42, row.scrollOffset)
    }

    @Test
    fun `getStack orders by position ascending regardless of insert order`() = runBlocking {
        dao.insertAll(
            listOf(
                BrowseStackEntity(position = 2, sid = "1024", cid = "b", displayName = "B"),
                BrowseStackEntity(position = 0, sid = "1024", cid = null, displayName = "Root"),
                BrowseStackEntity(position = 1, sid = "1024", cid = "a", displayName = "A"),
            ),
        )

        assertEquals(listOf("Root", "A", "B"), dao.getStack().map { it.displayName })
    }
}

@ExtendWith(RobolectricExtension::class)
class BrowseCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BrowseCacheDao

    private fun page(sid: String = "1024", cid: String = "a", rangeStart: Int = 0, cachedAt: Long = 1_000L) =
        BrowseCacheEntity(
            sid = sid,
            cid = cid,
            rangeStart = rangeStart,
            itemsJson = "[]",
            count = 0,
            returned = 0,
            optionIdsCsv = "",
            cachedAt = cachedAt,
        )

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.browseCacheDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `get misses on a sid-cid-rangeStart combination that was never cached`() = runBlocking {
        assertNull(dao.get(sid = "1024", cid = "a", rangeStart = 0))
    }

    @Test
    fun `upsert then get round-trips a page`() = runBlocking {
        dao.upsert(page(rangeStart = 0, cachedAt = 5_000L))

        val hit = dao.get(sid = "1024", cid = "a", rangeStart = 0)

        assertEquals(5_000L, hit?.cachedAt)
    }

    @Test
    fun `upsert replaces the existing row for the same key instead of duplicating it`() = runBlocking {
        dao.upsert(page(rangeStart = 0, cachedAt = 1_000L))
        dao.upsert(page(rangeStart = 0, cachedAt = 2_000L))

        assertEquals(listOf(2_000L), dao.getAllPages("1024", "a").map { it.cachedAt })
    }

    @Test
    fun `invalidate clears every page of a container, not just one range`() = runBlocking {
        dao.upsert(page(rangeStart = 0))
        dao.upsert(page(rangeStart = 100))
        dao.upsert(page(cid = "other", rangeStart = 0))

        dao.invalidate(sid = "1024", cid = "a")

        assertEquals(emptyList(), dao.getAllPages("1024", "a"))
        assertEquals(1, dao.getAllPages("1024", "other").size)
    }

    @Test
    fun `deleteOlderThan removes only rows cached before the cutoff`() = runBlocking {
        dao.upsert(page(rangeStart = 0, cachedAt = 1_000L))
        dao.upsert(page(rangeStart = 100, cachedAt = 9_000L))

        dao.deleteOlderThan(5_000L)

        assertEquals(listOf(9_000L), dao.getAllPages("1024", "a").map { it.cachedAt })
    }
}
