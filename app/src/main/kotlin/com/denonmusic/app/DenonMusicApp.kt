package com.denonmusic.app

import android.app.Application
import com.denonmusic.app.update.UpdatePrefsStore
import com.denonmusic.app.update.UpdateScheduler
import com.denonmusic.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class DenonMusicApp : Application() {

    /**
     * Reached through an entry point rather than `@Inject lateinit var`, for the same reason
     * [com.denonmusic.app.notification.NowPlayingService] and [MainActivity] both avoid it: field
     * injection is the one Hilt shape this project's Dagger/Kotlin pairing will not compile.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Graph {
        fun settingsRepository(): SettingsRepository
    }

    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.schedule(this, UpdatePrefsStore(this).frequency)
        migrateStoredSecrets()
    }

    /**
     * Any password stored before the app had somewhere encrypted to put one is still sitting in the
     * clear. Moving it is done once, here, before a screen can read settings - reading from the new
     * place without this would quietly log the user out of their own share.
     *
     * Off the main thread because it touches the device keystore on first use, and wrapped because
     * a failure to migrate is not a reason to fail to start: the settings screen can always be used
     * to enter the password again.
     */
    private fun migrateStoredSecrets() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                EntryPointAccessors.fromApplication(this@DenonMusicApp, Graph::class.java)
                    .settingsRepository()
                    .migrateSecretsOutOfPlainStorage()
            }
        }
    }
}
