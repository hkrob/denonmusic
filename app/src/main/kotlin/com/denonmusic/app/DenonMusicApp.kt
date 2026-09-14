package com.denonmusic.app

import android.app.Application
import com.denonmusic.app.update.UpdatePrefsStore
import com.denonmusic.app.update.UpdateScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DenonMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.schedule(this, UpdatePrefsStore(this).frequency)
    }
}
