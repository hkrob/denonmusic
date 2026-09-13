package com.denonmusic.data.browse

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowseStackDao {

    @Query("SELECT * FROM browse_stack ORDER BY position ASC")
    fun observeStack(): Flow<List<BrowseStackEntity>>

    @Query("SELECT * FROM browse_stack ORDER BY position ASC")
    suspend fun getStack(): List<BrowseStackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<BrowseStackEntity>)

    @Query("DELETE FROM browse_stack")
    suspend fun clear()

    @Query("DELETE FROM browse_stack WHERE position > :keepUpToPosition")
    suspend fun truncateAfter(keepUpToPosition: Int)

    @Query("UPDATE browse_stack SET scrollIndex = :scrollIndex, scrollOffset = :scrollOffset WHERE position = :position")
    suspend fun updateScroll(position: Int, scrollIndex: Int, scrollOffset: Int)

    /** Replaces the whole stack atomically so a shallower push can never leave deeper rows behind. */
    @Transaction
    suspend fun replaceStack(entries: List<BrowseStackEntity>) {
        clear()
        if (entries.isNotEmpty()) insertAll(entries)
    }
}

@Dao
interface BrowseCacheDao {

    @Query("SELECT * FROM browse_cache WHERE sid = :sid AND cid = :cid AND rangeStart = :rangeStart")
    suspend fun get(sid: String, cid: String, rangeStart: Int): BrowseCacheEntity?

    @Query("SELECT * FROM browse_cache WHERE sid = :sid AND cid = :cid ORDER BY rangeStart ASC")
    suspend fun getAllPages(sid: String, cid: String): List<BrowseCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BrowseCacheEntity)

    @Query("DELETE FROM browse_cache WHERE sid = :sid AND cid = :cid")
    suspend fun invalidate(sid: String, cid: String)

    @Query("DELETE FROM browse_cache WHERE cachedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    companion object {
        /** Sentinel for a source's top level, which the wire protocol addresses with no `cid`. */
        const val ROOT_CID: String = ""

        /** Restore paints instantly from a cache up to this old; revalidated in the background after. */
        const val TTL_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
