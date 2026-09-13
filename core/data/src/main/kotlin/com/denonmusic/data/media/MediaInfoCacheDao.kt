package com.denonmusic.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaInfoCacheDao {

    @Query("SELECT * FROM media_info_cache WHERE path = :path AND mtime = :mtime AND size = :size LIMIT 1")
    suspend fun get(path: String, mtime: Long, size: Long): MediaInfoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaInfoCacheEntity)

    @Query("DELETE FROM media_info_cache WHERE cachedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
