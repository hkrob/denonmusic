package com.denonmusic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.denonmusic.data.browse.BrowseCacheDao
import com.denonmusic.data.browse.BrowseCacheEntity
import com.denonmusic.data.browse.BrowseStackDao
import com.denonmusic.data.browse.BrowseStackEntity
import com.denonmusic.data.media.MediaInfoCacheDao
import com.denonmusic.data.media.MediaInfoCacheEntity

@Database(
    entities = [BrowseStackEntity::class, BrowseCacheEntity::class, MediaInfoCacheEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun browseStackDao(): BrowseStackDao
    abstract fun browseCacheDao(): BrowseCacheDao
    abstract fun mediaInfoCacheDao(): MediaInfoCacheDao

    companion object {
        const val NAME: String = "denonmusic.db"
    }
}
