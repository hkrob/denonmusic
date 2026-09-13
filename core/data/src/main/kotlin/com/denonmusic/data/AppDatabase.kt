package com.denonmusic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.denonmusic.data.browse.BrowseCacheDao
import com.denonmusic.data.browse.BrowseCacheEntity
import com.denonmusic.data.browse.BrowseStackDao
import com.denonmusic.data.browse.BrowseStackEntity

@Database(
    entities = [BrowseStackEntity::class, BrowseCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun browseStackDao(): BrowseStackDao
    abstract fun browseCacheDao(): BrowseCacheDao

    companion object {
        const val NAME: String = "denonmusic.db"
    }
}
