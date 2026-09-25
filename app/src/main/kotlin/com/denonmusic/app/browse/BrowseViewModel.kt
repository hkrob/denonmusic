package com.denonmusic.app.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.bridge.BridgeQueueItem
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosPlaybackStarter
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.data.browse.BrowseStackEntity
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.QueueCollection
import com.denonmusic.heos.QueueTargetResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseUiState(
    val connection: HeosConnectionState = HeosConnectionState.Disconnected,
    val breadcrumb: List<BrowseStackEntity> = emptyList(),
    val items: List<BrowseItem> = emptyList(),
    val isPlayableContainer: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val avrHost: String? = null,
)

/** One long-press menu offering, mapping 1:1 onto the wire `aid` values. */
enum class QueueAction(val criteria: AddCriteria, val label: String) {
    PlayNow(AddCriteria.PlayNow, "Play now"),
    PlayNext(AddCriteria.PlayNext, "Play next"),
    AddToEnd(AddCriteria.AddToEnd, "Add to end"),
    ReplaceAndPlay(AddCriteria.ReplaceAndPlay, "Replace queue and play"),
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val session: HeosSession,
    private val browseRepository: BrowseRepository,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val bridgeQueueController: BridgeQueueController,
    private val playbackStarter: HeosPlaybackStarter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var listingJob: Job? = null

    init {
        viewModelScope.launch {
            // Reactive, not a one-shot read: this screen can be created before the AVR host is ever
            // set (a fresh install lands here first), and it must not get stuck showing the "enter
            // receiver IP" gate forever just because the host was set afterwards from Settings (or
            // picked from LAN discovery there) rather than from this screen's own entry field.
            settings.settings.map { it.avrHost }.distinctUntilChanged().collectLatest { host ->
                _uiState.value = _uiState.value.copy(avrHost = host)
                host?.let { session.start(it) }
            }
        }
        viewModelScope.launch {
            session.state.collectLatest { state ->
                _uiState.value = _uiState.value.copy(connection = state)
                if (state is HeosConnectionState.Connected) bootstrap()
            }
        }
        viewModelScope.launch {
            browseRepository.observeStack().collectLatest { stack ->
                _uiState.value = _uiState.value.copy(breadcrumb = stack)
            }
        }
    }

    fun setAvrHost(host: String) {
        viewModelScope.launch {
            settings.setAvrHost(host)
            _uiState.value = _uiState.value.copy(avrHost = host)
            session.start(host)
        }
    }

    /**
     * Runs unconditionally on every HEOS reconnect - including the very first thing that happens on
     * every app launch once the session connects - so nothing in here may throw. A stray exception
     * (a transient HEOS error, a timeout right after connecting) previously crashed the app on that
     * same launch, every launch, since reconnecting and re-running this is exactly what happens next
     * time too; only clearing app data (wiping the persisted browse stack this reads) broke the loop.
     * See [BrowseRepository.restoreResolving]'s own hardening for the same reasoning.
     */
    private suspend fun bootstrap() {
        try {
            bootstrapUnsafe()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(message = "Couldn't load the library: ${e.message ?: e.toString()}")
        }
    }

    private suspend fun bootstrapUnsafe() {
        val client = session.heosClient ?: return

        val existingStack = browseRepository.currentStack()
        if (existingStack.isEmpty()) {
            val source = sourceRepository.detectAndPersist(client)
            if (source == null) {
                _uiState.value = _uiState.value.copy(message = "No queueable music source found. Add a share in the HEOS app.")
                return
            }
            browseRepository.replaceStack(listOf(BrowseLevel(sid = source.sid, cid = null, displayName = source.name)))
        } else {
            val resolved = browseRepository.restoreResolving(client)
            if (resolved.isEmpty()) {
                val source = sourceRepository.detectAndPersist(client)
                if (source != null) {
                    browseRepository.replaceStack(listOf(BrowseLevel(sid = source.sid, cid = null, displayName = source.name)))
                }
            }
        }
        refreshCurrentLevel()
    }

    /**
     * Reads the target level straight from [BrowseRepository] rather than `_uiState.breadcrumb`:
     * that field is populated by a separate collector on [BrowseRepository.observeStack] and is not
     * guaranteed to have caught up with a `replaceStack` this same call chain just made.
     */
    private suspend fun refreshCurrentLevel() {
        val client = session.heosClient ?: return
        val target = browseRepository.currentStack().lastOrNull() ?: return
        listingJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        listingJob = viewModelScope.launch {
            browseRepository.listing(client, target.sid, target.cid).collectLatest { listing ->
                _uiState.value = _uiState.value.copy(
                    items = listing.items,
                    isPlayableContainer = listing.isPlayableContainer,
                    isLoading = false,
                )
            }
        }
    }

    fun open(item: BrowseItem) {
        if (session.heosClient == null) return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        if (!item.isContainer || (item.cid == null && item.sid == null)) return
        viewModelScope.launch {
            // A row can carry its own sid instead of a cid - that's a nested source (e.g. one DLNA
            // server under the aggregate "Local Music" source) rather than a folder within the
            // current one, so it replaces the sid instead of extending the current one's cid.
            val newStack = _uiState.value.breadcrumb + BrowseStackEntity(
                position = _uiState.value.breadcrumb.size,
                sid = item.sid ?: current.sid,
                cid = item.sid?.let { null } ?: item.cid,
                displayName = item.name,
            )
            browseRepository.replaceStack(
                newStack.map { BrowseLevel(it.sid, it.cid, it.displayName) },
            )
            refreshCurrentLevel()
        }
    }

    fun goToBreadcrumb(position: Int) {
        viewModelScope.launch {
            browseRepository.truncateAfter(position)
            refreshCurrentLevel()
        }
    }

    fun onScroll(position: Int, index: Int, offset: Int) {
        viewModelScope.launch { browseRepository.updateScroll(position, index, offset) }
    }

    fun queue(item: BrowseItem, action: QueueAction) {
        val client = session.heosClient ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        viewModelScope.launch {
            val playerId = session.resolvePid() ?: run {
                _uiState.value = _uiState.value.copy(message = "No HEOS player found")
                return@launch
            }
            // playbackStarter, not client, because the user explicitly chose the primary HEOS-indexed
            // path - see HeosPlaybackStarter for why that has to relinquish bridge-mode ownership.
            runCatching {
                playbackStarter.addToQueue(
                    client = client,
                    pid = playerId,
                    sid = current.sid,
                    cid = item.cid ?: current.cid.orEmpty(),
                    mid = item.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${item.name}")
            }
        }
    }

    /**
     * Walks [current]'s whole subtree to gather everything queueable, not just what HEOS itself
     * marks playable at this level - a multi-disc album (`CD1`/`CD2` subfolders, no tracks at the
     * album's own level) never gets the server's own "playable container" flag, so a plain
     * `addToQueue(cid=current.cid)` would silently do nothing for it. See
     * [QueueTargetResolver.collect], which the LAN control API shares.
     */
    fun playAllCurrentContainer(action: QueueAction) {
        val client = session.heosClient ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        viewModelScope.launch {
            val playerId = session.resolvePid() ?: run {
                _uiState.value = _uiState.value.copy(message = "No HEOS player found")
                return@launch
            }
            val collected = runCatching { QueueTargetResolver.collect(client, current.sid, current.cid) }
                .getOrElse { e ->
                    _uiState.value = _uiState.value.copy(message = e.message ?: "Couldn't gather tracks")
                    return@launch
                }
            if (collected is QueueCollection.TooMany) {
                _uiState.value = _uiState.value.copy(
                    message = "Too many items to queue at once " +
                        "(limit ${collected.limit}) - open a smaller folder",
                )
                return@launch
            }
            val targets = (collected as QueueCollection.Complete).targets
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(message = "Nothing playable found")
                return@launch
            }
            runCatching { playbackStarter.addAll(client, playerId, targets, action.criteria) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
                }.onSuccess {
                    val parts = if (targets.size == 1) "" else " (${targets.size} parts)"
                    _uiState.value = _uiState.value.copy(message = "${action.label}: ${current.displayName}$parts")
                }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * The raw-URL tester: bypasses the bridge queue entirely and just plays exactly one stream via
     * `play_stream` - for exercising the receiver without any SMB share configured at all.
     */
    fun playBridgeUrl(url: String) {
        val client = session.heosClient ?: return
        viewModelScope.launch {
            val playerId = session.resolvePid() ?: run {
                _uiState.value = _uiState.value.copy(message = "No HEOS player found")
                return@launch
            }
            runCatching { client.playStream(playerId, url) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(message = e.message ?: "Stream failed") }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Streaming (degraded bridge mode)") }
        }
    }

    /** The manual "type a path" field: a one-item bridge queue, same mechanism as folder playback. */
    fun playBridgeFromSmb(path: String) {
        bridgeQueueController.replaceQueueAndPlay(listOf(path.toBridgeQueueItem()))
        _uiState.value = _uiState.value.copy(message = "Streaming (degraded bridge mode)")
    }

    /**
     * The SMB file browser's tap behaviour: replaces the bridge queue with every file in the current
     * folder and starts at [startIndex] - tapping any track plays the rest of its folder afterward,
     * the plan's "play a folder, not just one file" gap that a raw `play_stream` call alone can't
     * cover (no real HEOS queue exists for it).
     */
    fun playBridgeFolder(paths: List<String>, startIndex: Int) {
        if (paths.isEmpty()) return
        bridgeQueueController.replaceQueueAndPlay(paths.map { it.toBridgeQueueItem() }, startIndex)
        _uiState.value = _uiState.value.copy(message = "Streaming (degraded bridge mode)")
    }

    /** Appends without disturbing whatever's already playing - the browser's "add to queue" action. */
    fun addBridgeToQueue(paths: List<String>) {
        if (paths.isEmpty()) return
        bridgeQueueController.addToQueue(paths.map { it.toBridgeQueueItem() })
        _uiState.value = _uiState.value.copy(message = "Added ${paths.size} to bridge queue")
    }

    private fun String.toBridgeQueueItem() = BridgeQueueItem(path = this, displayName = substringAfterLast('/'))
}
