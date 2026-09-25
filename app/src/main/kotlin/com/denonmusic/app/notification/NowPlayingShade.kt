package com.denonmusic.app.notification

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the notification is currently doing, reported into Settings.
 *
 * This exists because the notification failed on a phone that could not be plugged in, twice, and
 * both fixes were guesses. A notification that does not appear says nothing about why - the
 * interesting failures (the system refusing a foreground service, the permission being absent) all
 * happen out of sight. Now the phone can say it out loud.
 */
sealed interface ShadeStatus {
    /** Nothing playing, so nothing to show. */
    data object Idle : ShadeStatus

    /** Showing, as a foreground service - the intended state. */
    data object Running : ShadeStatus

    /** Showing, but the system refused the foreground service, so it may be dropped under memory pressure. */
    data class Degraded(val reason: String) : ShadeStatus

    /** Not showing, and this is why. */
    data class Blocked(val reason: String) : ShadeStatus
}

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

    private val _status = MutableStateFlow<ShadeStatus>(ShadeStatus.Idle)
    val status: StateFlow<ShadeStatus> = _status.asStateFlow()

    fun report(status: ShadeStatus) {
        _status.value = status
    }

    fun setShowing(show: Boolean) {
        if (show == showing) return
        showing = show
        if (show) {
            runCatching { NowPlayingService.start(context) }
                .onFailure { _status.value = ShadeStatus.Blocked("could not start: " + it.describe()) }
        } else {
            runCatching { NowPlayingService.stop(context) }
            _status.value = ShadeStatus.Idle
        }
    }
}

/** Exception class plus message - the class alone is what names the Android rule that was broken. */
internal fun Throwable.describe(): String =
    this::class.java.simpleName + (message?.let { ": " + it } ?: "")
