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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
            val saved = settings.settings.first()
            _uiState.value = _uiState.value.copy(avrHost = saved.avrHost)
            saved.avrHost?.let { session.start(it) }
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

    private suspend fun bootstrap() {
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
        if (!item.isContainer || item.cid == null) return
        viewModelScope.launch {
            val newStack = _uiState.value.breadcrumb + BrowseStackEntity(
                position = _uiState.value.breadcrumb.size,
                sid = current.sid,
                cid = item.cid,
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

    fun playAllCurrentContainer(action: QueueAction) {
        val client = session.heosClient ?: return
        val playerId = pid ?: return
        val current = _uiState.value.breadcrumb.lastOrNull() ?: return
        val cid = current.cid ?: return
        viewModelScope.launch {
            runCatching {
                client.addToQueue(pid = playerId, sid = current.sid, cid = cid, criteria = action.criteria)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "Queue action failed")
            }.onSuccess {
                bridgeQueueController.clear()
                _uiState.value = _uiState.value.copy(message = "${action.label}: ${current.displayName}")
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
}
