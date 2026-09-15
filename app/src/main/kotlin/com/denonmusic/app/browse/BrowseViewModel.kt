package com.denonmusic.app.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.bridge.BridgeQueueController
import com.denonmusic.app.bridge.BridgeQueueItem
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.data.browse.BrowseStackEntity
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.HeosClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var pid: String? = null
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
        runCatching { client.getPlayers() }.getOrNull()?.firstOrNull()?.let { pid = it.pid }

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
        val playerId = pid ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        viewModelScope.launch {
            runCatching {
                client.addToQueue(
                    pid = playerId,
                    sid = current.sid,
                    cid = item.cid ?: current.cid.orEmpty(),
                    mid = item.mid,
                    criteria = action.criteria,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                // The user explicitly chose the primary HEOS-indexed path - relinquish the bridge
                // queue's ownership of Now Playing / transport control back to real HEOS behaviour.
                bridgeQueueController.clear()
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${item.name}")
            }
        }
    }

    /**
     * Walks [current]'s whole subtree to gather everything queueable, not just what HEOS itself
     * marks playable at this level - a multi-disc album (`CD1`/`CD2` subfolders, no tracks at the
     * album's own level) never gets the server's own "playable container" flag, so a plain
     * `addToQueue(cid=current.cid)` would silently do nothing for it. See [collectQueueTargets].
     */
    fun playAllCurrentContainer(action: QueueAction) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        viewModelScope.launch {
            val targets = runCatching { collectQueueTargets(client, current.sid, current.cid) }
                .getOrElse { e ->
                    _uiState.value = _uiState.value.copy(message = e.message ?: "Couldn't gather tracks")
                    return@launch
                }
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(message = "Nothing playable found")
                return@launch
            }
            if (targets.size > MAX_QUEUE_TARGETS) {
                _uiState.value = _uiState.value.copy(
                    message = "Too many items to queue at once (limit $MAX_QUEUE_TARGETS) - open a smaller folder",
                )
                return@launch
            }
            runCatching {
                // Only the first part carries the user's chosen criteria (replace-and-play, or
                // play-now); every part after it always appends, or a multi-part "replace and play"
                // would replace the queue anew on each call and leave only the last part in it.
                targets.forEachIndexed { index, target ->
                    val criteria = if (index == 0) action.criteria else AddCriteria.AddToEnd
                    client.addToQueue(pid = playerId, sid = target.sid, cid = target.cid, mid = target.mid, criteria = criteria)
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                bridgeQueueController.clear()
                val parts = if (targets.size == 1) "" else " (${targets.size} parts)"
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${current.displayName}$parts")
            }
        }
    }

    private data class QueueTarget(val sid: String, val cid: String, val mid: String?)

    /**
     * Recursively resolves every leaf under `sid`/`cid` into something a single `addToQueue` call can
     * take: a playable container (HEOS flattens its own tracks server-side - one call covers a whole
     * album or disc), or, failing that, each of its tracks individually by `mid`. Bounded by
     * [MAX_RECURSE_DEPTH]/[MAX_QUEUE_TARGETS] so a tap on a huge aggregate source (an artist index, a
     * whole DLNA library) can't silently try to enqueue thousands of tracks.
     */
    private suspend fun collectQueueTargets(client: HeosClient, sid: String, cid: String?, depth: Int = 0): List<QueueTarget> {
        if (depth > MAX_RECURSE_DEPTH) return emptyList()
        val items = mutableListOf<BrowseItem>()
        var isPlayable = false
        client.browseAll(sid, cid).collect { page ->
            items += page.items
            isPlayable = isPlayable || page.isPlayableContainer
        }
        val subContainers = items.filter { it.isContainer && it.sid == null && it.cid != null }
        if (subContainers.isEmpty()) {
            return if (isPlayable && cid != null) {
                listOf(QueueTarget(sid, cid, null))
            } else {
                items.filter { it.isTrack }.map { QueueTarget(sid, it.cid ?: cid.orEmpty(), it.mid) }
            }
        }
        val results = mutableListOf<QueueTarget>()
        for (sub in subContainers) {
            results += collectQueueTargets(client, sid, sub.cid, depth + 1)
            if (results.size > MAX_QUEUE_TARGETS) break
        }
        return results
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
        val playerId = pid ?: return
        viewModelScope.launch {
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

    private companion object {
        const val MAX_RECURSE_DEPTH = 6
        const val MAX_QUEUE_TARGETS = 300
    }
}
