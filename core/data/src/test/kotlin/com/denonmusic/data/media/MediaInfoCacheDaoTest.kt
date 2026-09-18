package com.denonmusic.data.media

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

/**
 * Pins the path+mtime+size cache-key logic [MediaInfoCacheEntity]'s own doc calls out as load-bearing:
 * any one of the three changing (a re-encode, a replaced file at the same path) must be a cache miss,
 * with no separate invalidation path required to keep it correct.
 */
@ExtendWith(RobolectricExtension::class)
class MediaInfoCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaInfoCacheDao

    private fun entity(path: String = "/music/a.flac", mtime: Long = 1_000L, size: Long = 500L, cachedAt: Long = 1L) =
        MediaInfoCacheEntity(
            path = path,
            mtime = mtime,
            size = size,
            container = "Flac",
            sampleRateHz = 192_000,
            bitsPerSample = 24,
            channels = 2,
            bitrateKbps = null,
            isVbr = null,
            cachedAt = cachedAt,
        )

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.mediaInfoCacheDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `hits on an exact path-mtime-size match`() = runBlocking {
        dao.upsert(entity())

        val hit = dao.get(path = "/music/a.flac", mtime = 1_000L, size = 500L)

        assertEquals(192_000, hit?.sampleRateHz)
    }

    @Test
    fun `misses when the mtime changed at the same path - a re-encode in place`() = runBlocking {
        dao.upsert(entity(mtime = 1_000L))

        assertNull(dao.get(path = "/music/a.flac", mtime = 2_000L, size = 500L))
    }

    @Test
    fun `misses when the size changed at the same path and mtime`() = runBlocking {
        dao.upsert(entity(size = 500L))

        assertNull(dao.get(path = "/music/a.flac", mtime = 1_000L, size = 999L))
    }

    @Test
    fun `misses for a different path even with the same mtime and size`() = runBlocking {
        dao.upsert(entity(path = "/music/a.flac"))

        assertNull(dao.get(path = "/music/b.flac", mtime = 1_000L, size = 500L))
    }

    @Test
    fun `upsert replaces the row for the same key rather than duplicating it`() = runBlocking {
        dao.upsert(entity(cachedAt = 1L))
        dao.upsert(entity(cachedAt = 2L))

        assertEquals(2L, dao.get(path = "/music/a.flac", mtime = 1_000L, size = 500L)?.cachedAt)
    }

    @Test
    fun `deleteOlderThan removes only entries cached before the cutoff`() = runBlocking {
        dao.upsert(entity(path = "/music/old.flac", cachedAt = 1_000L))
        dao.upsert(entity(path = "/music/new.flac", cachedAt = 9_000L))

        dao.deleteOlderThan(5_000L)

        assertNull(dao.get(path = "/music/old.flac", mtime = 1_000L, size = 500L))
        assertEquals(9_000L, dao.get(path = "/music/new.flac", mtime = 1_000L, size = 500L)?.cachedAt)
    }
}
