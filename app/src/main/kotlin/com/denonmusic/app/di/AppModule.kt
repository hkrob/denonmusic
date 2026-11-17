package com.denonmusic.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.heos.BridgeQueueClearer
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.avr.AvrSession
import com.denonmusic.data.AppDatabase
import com.denonmusic.data.browse.BrowseCacheDao
import com.denonmusic.data.browse.BrowseStackDao
import com.denonmusic.data.media.MediaInfoCacheDao
import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.settings.EncryptedSecretStore
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.HeosSession
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
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(dataStore, EncryptedSecretStore(context))

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Every table here is a cache or saved UI state, not a record of anything that can't be
            // re-derived: it repopulates itself from the receiver/filesystem on a miss, or the user
            // just re-navigates. So a schema bump destroying and recreating the database costs an
            // installed update one cold cache/reset browse position and scroll offsets, never real
            // data - an acceptable one-time cost, not something worth a Migration per version.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBrowseStackDao(db: AppDatabase): BrowseStackDao = db.browseStackDao()

    @Provides
    fun provideBrowseCacheDao(db: AppDatabase): BrowseCacheDao = db.browseCacheDao()

    @Provides
    fun provideMediaInfoCacheDao(db: AppDatabase): MediaInfoCacheDao = db.mediaInfoCacheDao()

    @Provides
    fun provideSearchIndexDao(db: AppDatabase): SearchIndexDao = db.searchIndexDao()

    /**
     * [HeosPlaybackStarter] only ever needs to clear the bridge queue, so it asks for the narrow
     * interface rather than the whole controller - which keeps it fakeable in a unit test.
     */
    @Provides
    @Singleton
    fun provideBridgeQueueClearer(controller: BridgeQueueController): BridgeQueueClearer =
        BridgeQueueClearer { controller.clear() }

    /**
     * The two session classes live in the pure-JVM protocol modules, which carry no injection
     * annotations, so their singleton-ness is declared here instead. It is not optional: every
     * control surface must share the one set of sockets, or two of them reconnect over each other.
     */
    @Provides
    @Singleton
    fun provideHeosSession(): HeosSession = HeosSession()

    @Provides
    @Singleton
    fun provideAvrSession(): AvrSession = AvrSession()
}
