package com.denonmusic.app.notification

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops [NowPlayingService] as something becomes worth showing.
 *
 * Separate from [NowPlayingNotifier] only so the notifier - which the service itself injects - does
 * not have to hold a `Context`. Calls are idempotent: the service is started once and thereafter
 * updates itself from the notifier's flow, so a state change per second costs nothing.
 */
@Singleton
class NowPlayingShade @Inject constructor(@ApplicationContext private val context: Context) {

    private var showing = false

    fun setShowing(show: Boolean) {
        if (show == showing) return
        showing = show
        if (show) NowPlayingService.start(context) else NowPlayingService.stop(context)
    }
}
