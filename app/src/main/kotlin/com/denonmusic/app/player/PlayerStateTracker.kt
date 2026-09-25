package com.denonmusic.app.player

import com.denonmusic.app.avr.AvrSession
import com.denonmusic.app.avr.BitPerfectPolicyController
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.app.lancontrol.LanControlManager
import com.denonmusic.app.notification.NowPlayingNotifier
import com.denonmusic.app.notification.NowPlayingShade
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.PlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns "what is the receiver doing right now" for the whole app: the player id, what is playing,
 * transport state, volume, play mode, the queue and the AVR's signal detail.
 *
 * This lived in [PlayerViewModel] until the notification needed it. A view model dies with the
 * screen, so anything mirroring one goes stale the moment the app is closed - which is exactly when
 * a notification is worth having. Moving the state here, to a singleton that outlives any screen,
 * is what lets the shade keep telling the truth with no UI attached, and it does so without a
 * second poller: there is still exactly one resync loop and one event subscription.
 *
 * It is not always running. [attach]/[detach] count who actually needs live state - a screen, or
 * the notification service - and the loop stops when nobody does, so a backgrounded app with
 * nothing playing is not quietly polling a receiver forever.
 */
@Singleton
class PlayerStateTracker @Inject constructor(
    private val session: HeosSession,
    private val avrSession: AvrSession,
    private val settings: SettingsRepository,
    private val bridgeQueueController: BridgeQueueController,
    private val lanControlManager: LanControlManager,
    private val bitPerfectPolicyController: BitPerfectPolicyController,
    private val notifier: NowPlayingNotifier,
    private val shade: NowPlayingShade,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var bridgeJob: Job? = null
    private var eventsJob: Job? = null
    private var resyncJob: Job? = null
    private var lastPlayState: PlayState? = null

    /** How many of "a screen" and "the notification" currently need live state. */
    private var users = 0

    /** True while a screen is one of them: the shade may then follow the UI rather than playback. */
    private var uiAttached = false

    init {
        scope.launch {
            _state.collect { publishToShade(it) }
        }
    }

    /**
     * [fromUi] distinguishes a screen from the notification service, because the two want different
     * things from the shade: with a screen attached it can show a paused track (the user is looking
     * at the app and may come back to it), while with only the service left the notification is
     * only justified while something is actually playing - see [publishToShade].
     */
    @Synchronized
    fun attach(fromUi: Boolean) {
        if (fromUi) uiAttached = true
        users++
        if (users == 1) start()
    }

    @Synchronized
    fun detach(fromUi: Boolean) {
        if (fromUi) uiAttached = false
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
        // A screen going away can make an idle notification unjustified, so re-evaluate now rather
        // than waiting for the next state change, which may never come.
        publishToShade(_state.value)
    }

    private fun start() {
        // Idempotent, and load-bearing for more than the LAN API: this is what connects
        // HeosSession and AvrSession from the saved host. Every other caller of `start` on those is
        // a screen, so without this the notification would have no receiver to talk to once the UI
        // is gone - the shade would show whatever was last true and never move again.
        lanControlManager.ensureStarted()
        sessionJob = scope.launch {
            session.state.collectLatest { state ->
                if (state is HeosConnectionState.Connected) {
                    bootstrap()
                } else {
                    eventsJob?.cancel()
                    resyncJob?.cancel()
                    _state.value = PlayerUiState()
                }
            }
        }
        bridgeJob = scope.launch {
            bridgeQueueController.state.collectLatest { bridgeState ->
                _state.update { it.copy(bridgeQueue = bridgeState) }
            }
        }
    }

    private fun stop() {
        sessionJob?.cancel()
        bridgeJob?.cancel()
        eventsJob?.cancel()
        resyncJob?.cancel()
        sessionJob = null
        bridgeJob = null
    }

    /**
     * The notification exists while there is a track and the receiver is playing or paused.
     *
     * Paused has to count. Dropping the notification on pause would make it a one-way door: with
     * the app closed, the control that paused the music would be the last one the user ever got,
     * and resuming would mean going and finding the app. Stopped is the state that ends it, which
     * is also what the notification's own Stop button and its dismissal do.
     *
     * While a screen is attached the shade follows the screen instead, so it is there to hand the
     * moment the user leaves the app, rather than appearing a beat later.
     */
    private fun publishToShade(state: PlayerUiState) {
        notifier.publish(state)
        val live = state.playState == PlayState.Play || state.playState == PlayState.Pause
        shade.setShowing(!notifier.snapshot.value.isEmpty && (uiAttached || live))
    }

    /** For the optimistic updates [PlayerViewModel]'s own actions make ahead of the next refresh. */
    fun update(transform: (PlayerUiState) -> PlayerUiState) = _state.update(transform)

    private suspend fun bootstrap() {
        // The AVR and HEOS ports live on the same box, so the same host serves both. Started here
        // too (not only from AvrViewModel) so the bit-perfect policy applies even if the user has
        // never opened the AVR tab.
        runCatching { settings.settings.first() }.getOrNull()?.avrHost?.let { avrSession.start(it) }
        startPeriodicResync()
    }

    /**
     * A poll-based safety net on top of [subscribeEvents]'s event-driven updates - independent of
     * [HeosSession]'s own now-heartbeated event socket, since even a healthy connection can miss the
     * odd event, and a third party (the receiver's own remote, another HEOS app) changing state
     * doesn't necessarily fire an event this app happens to be listening for. Never lets the UI drift
     * further than this interval from ground truth, whatever the cause.
     *
     * Also the only place [resolvePlayerAndRefresh] runs from, so re-resolving the player id is on
     * the same clock as everything else - see its own doc for why that can't be a one-shot lookup.
     */
    private fun startPeriodicResync() {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            while (isActive) {
                resolvePlayerAndRefresh()
                delay(
                    when {
                        _state.value.noHeosPlayerFound -> NO_PLAYER_RETRY_INTERVAL_MS
                        // Nobody is looking at a screen: the notification still has to stay
                        // truthful, but it does not need the responsiveness a visible UI does.
                        !uiAttached -> BACKGROUND_RESYNC_INTERVAL_MS
                        else -> RESYNC_INTERVAL_MS
                    },
                )
            }
        }
    }

    /**
     * Re-resolves the active HEOS player on every tick via [HeosSession.resolvePid], which never
     * caches - see its doc for the receiver behaviour that makes a latched pid a trap.
     *
     * Polling here both recovers automatically once the receiver's registry comes back and surfaces
     * the gap to the user in the meantime via [PlayerUiState.noHeosPlayerFound], instead of a
     * generic-looking "nothing playing".
     */
    private suspend fun resolvePlayerAndRefresh() {
        val playerPid = session.resolvePid()
        if (playerPid == null) {
            if (!_state.value.noHeosPlayerFound) {
                eventsJob?.cancel()
                _state.update {
                    it.copy(
                        noHeosPlayerFound = true,
                        pid = null,
                        nowPlaying = null,
                        playState = null,
                        queue = emptyList(),
                    )
                }
            }
            return
        }
        val recovered = _state.value.pid != playerPid
        _state.update { it.copy(pid = playerPid, noHeosPlayerFound = false) }
        if (recovered) subscribeEvents(playerPid)
        refreshAll(playerPid)
        refreshQueue(playerPid)
    }

    private suspend fun refreshAll(pid: String) {
        val client = session.heosClient ?: return
        val nowPlaying = runCatching { client.getNowPlaying(pid) }.getOrNull()
        val playState = runCatching { client.getPlayState(pid) }.getOrNull()
        val volume = runCatching { client.getVolume(pid) }.getOrNull()
        val playMode = runCatching { client.getPlayMode(pid) }.getOrNull()
        _state.update {
            it.copy(
                nowPlaying = nowPlaying,
                playState = playState,
                volume = volume,
                repeat = playMode?.repeat,
                shuffle = playMode?.shuffle,
            )
        }
        if (playState == PlayState.Play && lastPlayState != PlayState.Play) {
            applyBitPerfectPolicyOnPlaybackStart()
        }
        lastPlayState = playState
        refreshTechnicalInfo()
    }

    /**
     * Reads the signal the AVR is actually receiving right now. Unlike [applyBitPerfectPolicyOnPlaybackStart]
     * this isn't gated on a play-state edge: [refreshAll] itself only runs on a handful of HEOS
     * events (now-playing/state/volume/repeat/shuffle changed), not every progress tick, so querying
     * the AVR's telnet port here as often as that runs is cheap enough not to need its own gate.
     */
    private suspend fun refreshTechnicalInfo() {
        val client = avrSession.avrClient ?: return
        val info = TechnicalInfo(
            signalType = runCatching { client.signalType() }.getOrNull(),
            sampleRateKhz = runCatching { client.sampleRateKhz() }.getOrNull(),
            activeOutputChannels = runCatching { client.outputChannels() }.getOrDefault(emptyList()).size,
        )
        _state.update { it.copy(technicalInfo = info) }
    }

    /**
     * The plan's bit-perfect policy applies "on queue-start": the moment playback transitions into
     * [PlayState.Play], not on every progress tick. `lastPlayState` in [refreshAll] is what turns a
     * level (current state) into that edge (state that just changed).
     */
    private suspend fun applyBitPerfectPolicyOnPlaybackStart() {
        bitPerfectPolicyController.applyStored(avrSession.avrClient)
    }

    suspend fun refreshQueue(pid: String) {
        val client = session.heosClient ?: return
        val items = runCatching { client.getQueue(pid) }.getOrNull().orEmpty()
        _state.update { it.copy(queue = items) }
    }

    private fun subscribeEvents(pid: String) {
        eventsJob?.cancel()
        val events = session.events ?: return
        eventsJob = scope.launch {
            events.collect { frame ->
                when (frame.eventName) {
                    "player_now_playing_changed", "player_state_changed", "player_volume_changed",
                    "repeat_mode_changed", "shuffle_mode_changed",
                    -> refreshAll(pid)
                    "player_now_playing_progress" -> {
                        val position = frame.attributes["cur_pos"]?.toLongOrNull() ?: 0L
                        val duration = frame.attributes["duration"]?.toLongOrNull() ?: 0L
                        _state.update { it.copy(progress = Progress(position, duration)) }
                    }
                    "player_queue_changed" -> refreshQueue(pid)
                }
                if (frame.eventName == "player_volume_changed") {
                    frame.attributes["mute"]?.let { mute ->
                        _state.update { it.copy(muted = mute == "on") }
                    }
                }
            }
        }
    }

    private companion object {
        const val RESYNC_INTERVAL_MS = 15_000L

        /** Tighter than [RESYNC_INTERVAL_MS] while no player is found, so recovery (e.g. a power cycle) shows up promptly. */
        const val NO_PLAYER_RETRY_INTERVAL_MS = 5_000L

        /** Only the notification is watching: still truthful, but a quarter of the chatter. */
        const val BACKGROUND_RESYNC_INTERVAL_MS = 60_000L
    }
}
