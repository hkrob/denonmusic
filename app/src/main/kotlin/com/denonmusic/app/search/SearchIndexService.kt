package com.denonmusic.app.search

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.denonmusic.app.MainActivity
import com.denonmusic.app.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs [SearchIndexRepository.runReindex] as a foreground service, so a crawl over a large library
 * (tens of minutes - measured, see docs/local-setup.md 2026-10-02) survives the app leaving the
 * screen instead of being killed partway with nothing to show for it.
 *
 * That silent kill was the whole bug this service exists to fix: before it, the crawl ran on
 * [SearchIndexRepository]'s own plain singleton scope, which Android is willing to end at any point
 * once nothing is in the foreground. A user with a large library backgrounding the app to wait came
 * back to "not indexed" with no error shown, because there was no failure to catch - the process
 * carrying the coroutine was simply gone. A foreground service is the one thing on Android that
 * trades a persistent notification for not being that killable - the same reasoning
 * [com.denonmusic.app.notification.NowPlayingService] already relies on for playback controls.
 *
 * Unlike that service, this one is not meant to outlive its own job: it starts the crawl, shows its
 * progress, and stops itself the moment [SearchIndexRepository.status] reaches a terminal state
 * ([SearchIndexStatus.Ready] or [SearchIndexStatus.Failed]), leaving one final, dismissible
 * notification behind rather than an ongoing one.
 */
class SearchIndexService : Service() {

    /** Field injection isn't available on a Service in this project - see
     * [com.denonmusic.app.notification.NowPlayingService]'s own [Graph] for why. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Graph {
        fun searchIndexRepository(): SearchIndexRepository
    }

    private val graph: Graph by lazy { EntryPointAccessors.fromApplication(applicationContext, Graph::class.java) }
    private val repository: SearchIndexRepository by lazy { graph.searchIndexRepository() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var crawlJob: Job? = null
    private var started = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Notification updates only - termination is driven by the crawl job finishing in
        // onStartCommand below, never by what this collector observes. repository.status's first
        // emission to a brand new collector is whatever it already was - Ready from a previous
        // successful build, or Failed from a previous attempt - and both are terminal states. Ending
        // the service the moment either is merely *seen*, rather than when this service's own crawl
        // actually reaches one, meant REINDEX after any prior Ready or Failed tore the service down
        // before (sometimes during) starting the very crawl it was meant to run.
        scope.launch {
            repository.status.collect { status -> if (started) notify(build(status)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) startForegroundOrFallBack(build(repository.status.value))
        if (crawlJob?.isActive != true) {
            crawlJob = scope.launch {
                repository.runReindex()
                finish(repository.status.value)
            }
        }
        // Not START_STICKY: an index this service didn't itself just start has no user waiting on
        // it, and restarting silently after the system killed this process for memory would just
        // repeat whatever starved it the first time. A killed crawl needs a human to tap Reindex
        // again, the same as any other failure here.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+'s runtime cap on a `dataSync` foreground service (6 hours in a rolling 24h window)
     * - nowhere near what one crawl takes (tens of minutes, measured), but the override is mandatory
     * for this foreground service type regardless, or the system ends the process outright rather
     * than calling back. Cancelling [crawlJob] routes through [SearchIndexRepository.runReindex]'s
     * own `CancellationException` handling, which reports [SearchIndexStatus.Failed] before
     * rethrowing - so this still leaves an honest notification behind instead of just vanishing.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        crawlJob?.cancel()
        stopSelf()
    }

    /** Detaches from the foreground and leaves one dismissible notification, rather than an ongoing
     * one nobody can clear - the crawl is over, successfully or not. */
    private fun finish(status: SearchIndexStatus) {
        if (stopping) return
        stopping = true
        if (started) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            notify(build(status))
        }
        stopSelf()
    }

    private fun build(status: SearchIndexStatus): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text, ongoing) = when (status) {
            is SearchIndexStatus.Indexing -> Triple("Indexing library...", "${status.found} found so far", true)
            is SearchIndexStatus.Ready -> Triple("Indexing complete", "${status.count} items indexed", false)
            is SearchIndexStatus.Failed -> Triple("Indexing failed", status.message, false)
            is SearchIndexStatus.NeverIndexed -> Triple("Indexing library...", "Starting...", true)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            // Only while running - a progress bar has no business on the terminal notification.
            .apply { if (ongoing) setProgress(0, 0, true) }
            .build()
    }

    private fun notify(notification: android.app.Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    /** Same foreground-or-fallback dance as [com.denonmusic.app.notification.NowPlayingService] -
     * see its own doc for why every branch here exists. */
    private fun startForegroundOrFallBack(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start in the foreground for indexing; showing a plain notification instead", e)
            notify(notification)
            started = true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Library indexing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while the whole-library search index is being built."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SearchIndexService"
        private const val CHANNEL_ID = "search_index"
        private const val NOTIFICATION_ID = 2

        /** `startForegroundService`, not `startService` - see
         * [com.denonmusic.app.notification.NowPlayingService.start] for why. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SearchIndexService::class.java))
        }
    }
}
