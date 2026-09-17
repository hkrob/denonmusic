package com.denonmusic.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.heos.BridgeQueueClearer
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.data.AppDatabase
import com.denonmusic.data.browse.BrowseCacheDao
import com.denonmusic.data.browse.BrowseStackDao
import com.denonmusic.data.media.MediaInfoCacheDao
import com.denonmusic.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(SettingsRepository.PREFERENCES_NAME)
        }

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository =
        SettingsRepository(dataStore)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Pre-release: no installed schema is worth preserving yet, and every cache table here
            // repopulates itself from the network/filesystem on a miss.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBrowseStackDao(db: AppDatabase): BrowseStackDao = db.browseStackDao()

    @Provides
    fun provideBrowseCacheDao(db: AppDatabase): BrowseCacheDao = db.browseCacheDao()

    @Provides
    fun provideMediaInfoCacheDao(db: AppDatabase): MediaInfoCacheDao = db.mediaInfoCacheDao()

    /**
     * [HeosPlaybackStarter] only ever needs to clear the bridge queue, so it asks for the narrow
     * interface rather than the whole controller - which keeps it fakeable in a unit test.
     */
    @Provides
    @Singleton
    fun provideBridgeQueueClearer(controller: BridgeQueueController): BridgeQueueClearer =
        BridgeQueueClearer { controller.clear() }
}
