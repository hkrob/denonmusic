package com.denonmusic.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.denonmusic.app.MainActivity
import com.denonmusic.app.player.PlayerStateTracker
import com.denonmusic.heos.PlayState
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
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws the now-playing notification and turns its buttons back into receiver commands.
 *
 * A foreground service, because a plain notification would be killed the moment the app is no
 * longer visible - which is exactly when these controls are worth having.
 *
 * It outlives the UI. [PlayerStateTracker] is a singleton that keeps resolving the player and
 * refreshing its state for as long as this service is attached to it, so swiping the app away
 * leaves the notification both present and correct - verified by changing the track on the
 * receiver itself, with no screen open, and watching the shade follow.
 *
 * It stops itself when there is nothing left to show: the queue ran out, or playback stopped with
 * no screen attached (see [PlayerStateTracker.publishToShade]). Music on the receiver carries on
 * regardless either way - nothing here is in the audio path.
 */
class NowPlayingService : Service() {

    /**
     * Reached through an entry point rather than `@AndroidEntryPoint` + `@Inject lateinit var`.
     * Field injection is the one Hilt shape this project's Dagger/Kotlin pairing cannot compile -
     * it fails with "Unable to read Kotlin metadata due to unsupported metadata version" - which is
     * why `MainActivity` avoids it too. Constructor injection is not an option for a Service, so
     * this is the remaining way to ask the graph for something.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Graph {
        fun nowPlayingNotifier(): NowPlayingNotifier

        fun playerStateTracker(): PlayerStateTracker
    }

    private val graph: Graph by lazy { EntryPointAccessors.fromApplication(applicationContext, Graph::class.java) }
    private val notifier: NowPlayingNotifier by lazy { graph.nowPlayingNotifier() }
    private val tracker: PlayerStateTracker by lazy { graph.playerStateTracker() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSessionCompat? = null
    private var artJob: Job? = null
    private var shownArtUrl: String? = null
    private var art: Bitmap? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Keeps the state this notification shows being refreshed once the last screen is gone -
        // without this the shade would be a snapshot of whatever was true when the app closed.
        tracker.attach(fromUi = false)
        createChannel()
        mediaSession = MediaSessionCompat(this, "DenonMusic").apply { isActive = true }
        scope.launch {
            notifier.snapshot.collect { snapshot ->
                if (snapshot.isEmpty) stopSelf() else show(snapshot)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> scope.launch {
                val current = notifier.snapshot.value
                notifier.setPlayState(if (current.isPlaying) PlayState.Pause else PlayState.Play)
            }
            ACTION_NEXT -> scope.launch { notifier.playNext() }
            ACTION_STOP -> scope.launch {
                notifier.setPlayState(PlayState.Stop)
                stopSelf()
            }
        }
        // A snapshot may already be waiting: show it now so the very first start is not a race
        // against the collector above.
        notifier.snapshot.value.takeIf { !it.isEmpty }?.let { show(it) }
        // START_STICKY: if the system reclaims the process while the receiver is still playing,
        // coming back is the right answer - the tracker re-attaches and refreshes from the receiver,
        // so there is no stale state to inherit.
        return START_STICKY
    }

    override fun onDestroy() {
        tracker.detach(fromUi = false)
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private fun show(snapshot: NowPlayingSnapshot) {
        if (snapshot.artUrl != shownArtUrl) {
            shownArtUrl = snapshot.artUrl
            art = snapshot.artUrl?.let { ArtCache.byUrl[it] }
            artJob?.cancel()
            artJob = scope.launch {
                val loaded = loadArt(snapshot.artUrl)
                // Only repaint if the track has not moved on while the image was downloading.
                if (loaded != null && notifier.snapshot.value.artUrl == snapshot.artUrl) {
                    art = loaded
                    notify(notifier.snapshot.value)
                }
            }
        }
        notify(snapshot)
    }

    private fun notify(snapshot: NowPlayingSnapshot) {
        if (snapshot.isEmpty) return
        mediaSession?.publish(snapshot, art)
        val notification = build(snapshot)
        if (started) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
            started = true
        }
    }

    private fun build(snapshot: NowPlayingSnapshot): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPauseIcon = if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (snapshot.isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.title)
            .setContentText(listOfNotNull(snapshot.artist, snapshot.album).joinToString(" - ").ifBlank { null })
            .setLargeIcon(art)
            .setContentIntent(openApp)
            .setDeleteIntent(command(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(snapshot.isPlaying)
            .addAction(playPauseIcon, playPauseLabel, command(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next", command(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", command(ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    // Play/pause and next stay visible on the collapsed notification; stop is one
                    // expand away, since it is the one press here that is not easily undone.
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(command(ACTION_STOP)),
            )
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, NowPlayingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun MediaSessionCompat.publish(snapshot: NowPlayingSnapshot, artwork: Bitmap?) {
        setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot.album)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .build(),
        )
        setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP,
                )
                // The receiver owns the clock; this app never knows the true position to the
                // millisecond, so the shade is told not to run a progress animation of its own.
                .setState(
                    if (snapshot.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f,
                )
                .build(),
        )
    }

    private suspend fun loadArt(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        ArtCache.byUrl[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }?.also { ArtCache.byUrl[url] = it }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows what the receiver is playing, with transport controls."
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Small and bounded: album art for the handful of tracks a listening session actually shows. */
    private object ArtCache {
        val byUrl = ConcurrentHashMap<String, Bitmap>()
    }

    companion object {
        private const val CHANNEL_ID = "now_playing"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.denonmusic.app.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.denonmusic.app.NEXT"
        private const val ACTION_STOP = "com.denonmusic.app.STOP"

        fun start(context: Context) {
            context.startService(Intent(context, NowPlayingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NowPlayingService::class.java))
        }
    }
}
