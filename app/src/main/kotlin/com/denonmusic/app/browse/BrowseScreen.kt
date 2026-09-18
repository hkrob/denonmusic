package com.denonmusic.app.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.bridge.SmbBrowseViewModel
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.app.ui.fileTypeLabel
import com.denonmusic.heos.BrowseItem
import com.denonmusic.smb.SmbEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(viewModel: BrowseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("D E N O N M U S I C", style = Winamp.titleStyle) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                    actionIconContentColor = Winamp.Green,
                ),
                actions = {
                    if (state.items.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                QueueAction.entries.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        onClick = {
                                            expanded = false
                                            viewModel.playAllCurrentContainer(action)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(Winamp.Background).padding(padding)) {
            if (state.avrHost == null) {
                AvrHostEntry(onSubmit = viewModel::setAvrHost)
                return@Column
            }

            ConnectionBanner(state.connection)
            Breadcrumb(state.breadcrumb.map { it.displayName }, onClick = viewModel::goToBreadcrumb)

            // Mirrors the SMB browser's own "PLAY ALL IN FOLDER" / "ADD FOLDER TO QUEUE" buttons -
            // the equivalent top-bar icon existed but was easy to miss, so the same action gets a
            // visible label here too for the native HEOS-indexed path.
            if (state.items.isNotEmpty()) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = { viewModel.playAllCurrentContainer(QueueAction.ReplaceAndPlay) }) {
                        Text("PLAY ALL", style = Winamp.smallStyle, color = Winamp.Amber)
                    }
                    TextButton(onClick = { viewModel.playAllCurrentContainer(QueueAction.AddToEnd) }) {
                        Text("ADD ALL TO QUEUE", style = Winamp.smallStyle, color = Winamp.Amber)
                    }
                }
            }

            var bridgeOpen by remember { mutableStateOf(false) }

            if (state.isLoading && state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Winamp.Green)
                }
            } else if (state.items.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOTHING HERE.", style = Winamp.labelStyle, color = Winamp.GreenDim)
                        Text(
                            "No content indexed by HEOS yet - register a DLNA server or\n" +
                                "SMB network share in the HEOS app to browse normally here.",
                            style = Winamp.smallStyle,
                            color = Winamp.GreenDim,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Row(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(state.items) { item ->
                            BrowseRow(item = item, onOpen = { viewModel.open(item) }, onAction = { viewModel.queue(item, it) })
                        }
                    }
                    // sid 1024 (the only queueable source) answered browse/get_search_criteria with an
                    // empty payload - confirmed against the real receiver, so it has no server-side
                    // search to fall back on. This scrolls the already-fetched listing instead, which
                    // solves the same "find an artist in a long list" problem without a round trip.
                    if (state.items.size > JUMP_INDEX_MIN_ITEMS) {
                        AlphabetJumpIndex(
                            onLetterSelected = { letter ->
                                val index = state.items.indexOfFirst { it.jumpLetter() == letter }
                                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                            },
                        )
                    }
                }
            }

            // Always reachable, not just when HEOS has nothing indexed - the bridge is also how you
            // reach files outside whatever a DLNA/SMB source happens to have picked up.
            TextButton(onClick = { bridgeOpen = !bridgeOpen }) {
                Text(
                    if (bridgeOpen) "HIDE SMB MODE - PROXIED VIA ANDROID APP" else "SMB MODE - PROXIED VIA ANDROID APP",
                    style = Winamp.labelStyle,
                    color = Winamp.Amber,
                )
            }
            if (bridgeOpen) {
                Box(modifier = Modifier.heightIn(max = 420.dp)) {
                    LazyColumn {
                        item {
                            BridgeModeTester(
                                onPlayUrl = viewModel::playBridgeUrl,
                                onPlaySmbPath = viewModel::playBridgeFromSmb,
                                onPlayFolder = viewModel::playBridgeFolder,
                                onAddToQueue = viewModel::addBridgeToQueue,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val JUMP_INDEX_MIN_ITEMS = 20

/** '#' for anything that doesn't start with a letter, so a numbered album/track still gets a bucket. */
private fun BrowseItem.jumpLetter(): Char {
    val first = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
    return if (first.isLetter()) first else '#'
}

/**
 * A-Z plus '#', tap-to-scroll over the already-fetched listing - the fallback for sid 1024 (Local
 * Music), the only queueable source, which answered `browse/get_search_criteria` with an empty
 * payload on the real receiver, confirming it has no server-side search at all.
 */
@Composable
private fun AlphabetJumpIndex(onLetterSelected: (Char) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        (listOf('#') + ('A'..'Z')).forEach { letter ->
            Text(
                letter.toString(),
                style = Winamp.smallStyle,
                color = Winamp.Green,
                modifier = Modifier.clickable { onLetterSelected(letter) },
            )
        }
    }
}

@Composable
private fun AvrHostEntry(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("ENTER RECEIVER IP", style = Winamp.labelStyle, color = Winamp.Green)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textStyle = Winamp.labelStyle.copy(color = Winamp.Green),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Winamp.Green,
                unfocusedBorderColor = Winamp.BevelLight,
                cursorColor = Winamp.Green,
            ),
        )
        TextButton(onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }) {
            Text("CONNECT", style = Winamp.labelStyle, color = Winamp.Amber)
        }
    }
}

@Composable
private fun ConnectionBanner(state: HeosConnectionState) {
    val label = when (state) {
        is HeosConnectionState.Disconnected -> "DISCONNECTED"
        is HeosConnectionState.Connecting -> "CONNECTING..."
        is HeosConnectionState.Connected -> null
        is HeosConnectionState.Failed -> "CONNECTION FAILED: ${state.reason}"
    }
    label?.let {
        Text(it, style = Winamp.smallStyle, color = Winamp.Amber, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}

/**
 * Well-known source-name prefixes that read fine abbreviated and otherwise eat a disproportionate
 * share of the breadcrumb's width every time they show up (they're the top-level source name, so
 * they appear in *every* breadcrumb below them). Prefix, not exact match: a DLNA server's own HEOS
 * source name carries its hostname too (verified on a real device - "Plex Media Server: Sugar"), so
 * only the well-known part gets replaced and the rest is kept.
 */
private val BREADCRUMB_ABBREVIATIONS = listOf(
    "plex media server" to "Plex",
)

private fun breadcrumbLabel(name: String): String {
    val trimmed = name.trim()
    val match = BREADCRUMB_ABBREVIATIONS.firstOrNull { (prefix, _) -> trimmed.lowercase().startsWith(prefix) }
        ?: return trimmed
    return match.second + trimmed.substring(match.first.length)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumb(names: List<String>, onClick: (Int) -> Unit) {
    // FlowRow instead of a LazyRow: a deep stack (source > artist > album > disc) can easily outrun
    // screen width, and wrapping to a second line beats forcing a horizontal scroll to see where you
    // are.
    FlowRow(
        modifier = Modifier.fillMaxWidth().background(Winamp.Panel).bevel().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        names.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onClick(index) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(breadcrumbLabel(name).uppercase(), style = Winamp.smallStyle, color = Winamp.Green)
                }
                if (index != names.lastIndex) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Winamp.GreenDim,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, onOpen: () -> Unit, onAction: (QueueAction) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (item.isContainer) onOpen() else if (item.isTrack) onAction(QueueAction.PlayNow) },
                    onLongClick = { menuExpanded = true },
                )
                .background(Winamp.Background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                if (item.isContainer) Icons.Filled.Folder else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (item.isContainer) Winamp.Amber else Winamp.Green,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = Winamp.labelStyle, color = Winamp.Green)
                    // Best-effort: only the HEOS-native SMB share's own track names carry a real
                    // extension - a DLNA/Plex-tagged title never does. See fileTypeLabel's own doc.
                    if (item.isTrack) {
                        fileTypeLabel(item.name)?.let {
                            Text(it, style = Winamp.smallStyle, color = Winamp.GreenDim, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                val subtitle = listOfNotNull(item.artist, item.album).joinToString(" — ")
                if (subtitle.isNotEmpty()) Text(subtitle, style = Winamp.smallStyle)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            QueueAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        menuExpanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

/**
 * The plan's phase-6 fallback, opt-in and clearly labelled per the plan's own wording, offered only
 * when there's nothing to browse via the primary HEOS-indexed path: play a file from the saved SMB
 * share through the phone's own Range-capable bridge server ([com.denonmusic.app.bridge.SmbBridgeService]),
 * or - the original raw-URL tester, kept for exercising the receiver without SMB credentials at all.
 */
@Composable
private fun BridgeModeTester(
    onPlayUrl: (String) -> Unit,
    onPlaySmbPath: (String) -> Unit,
    onPlayFolder: (paths: List<String>, startIndex: Int) -> Unit,
    onAddToQueue: (paths: List<String>) -> Unit,
    smbBrowseViewModel: SmbBrowseViewModel = hiltViewModel(),
) {
    var browserOpen by remember { mutableStateOf(false) }
    var smbPath by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("SMB MODE - PROXIED VIA ANDROID APP — no gapless, no DSD guarantee", style = Winamp.smallStyle, color = Winamp.Amber)

        TextButton(onClick = {
            browserOpen = !browserOpen
            if (browserOpen) smbBrowseViewModel.open()
        }) {
            Text(
                if (browserOpen) "HIDE SMB BROWSER" else "BROWSE SMB SHARE",
                style = Winamp.labelStyle,
                color = Winamp.Amber,
            )
        }
        if (browserOpen) {
            SmbFileBrowser(viewModel = smbBrowseViewModel, onPlayFolder = onPlayFolder, onAddToQueue = onAddToQueue)
        }

        Text("Or type a file path directly:", style = Winamp.smallStyle, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = smbPath,
            onValueChange = { smbPath = it },
            placeholder = { Text("Artist/Album/track.flac", style = Winamp.smallStyle) },
            textStyle = Winamp.smallStyle.copy(color = Winamp.Green),
            modifier = Modifier.padding(vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Winamp.Green,
                unfocusedBorderColor = Winamp.BevelLight,
            ),
        )
        TextButton(onClick = { if (smbPath.isNotBlank()) onPlaySmbPath(smbPath.trim()) }) {
            Text("PLAY FROM SMB", style = Winamp.labelStyle, color = Winamp.Amber)
        }

        Text("Or play a raw URL directly:", style = Winamp.smallStyle, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("http://host/file.flac", style = Winamp.smallStyle) },
            textStyle = Winamp.smallStyle.copy(color = Winamp.Green),
            modifier = Modifier.padding(vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Winamp.Green,
                unfocusedBorderColor = Winamp.BevelLight,
            ),
        )
        TextButton(onClick = { if (url.isNotBlank()) onPlayUrl(url.trim()) }) {
            Text("PLAY STREAM", style = Winamp.labelStyle, color = Winamp.Amber)
        }
    }
}

/** Breadcrumb + a bounded-height folder listing, so tapping through the SMB share doesn't need a typed path. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmbFileBrowser(
    viewModel: SmbBrowseViewModel,
    onPlayFolder: (paths: List<String>, startIndex: Int) -> Unit,
    onAddToQueue: (paths: List<String>) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val files = state.entries.filter { !it.isDirectory }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (state.hasCredentials) {
            false -> Text("Set SMB host/share in Settings first.", style = Winamp.smallStyle, color = Winamp.Amber)
            null -> Box(modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(color = Winamp.Green, modifier = Modifier.size(20.dp))
            }
            true -> {
                val crumbs = listOf("ROOT") + state.pathSegments
                FlowRow(
                    modifier = Modifier.fillMaxWidth().background(Winamp.Panel).bevel().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    crumbs.forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { viewModel.goToBreadcrumb(index - 1) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text(breadcrumbLabel(name).uppercase(), style = Winamp.smallStyle, color = Winamp.Green)
                            }
                            if (index != crumbs.lastIndex) {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = Winamp.GreenDim,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                if (state.entries.isNotEmpty()) {
                    // Reaches into subfolders (a multi-disc album's CD1/CD2, say), not just this
                    // level - see SmbBrowseViewModel.filesRecursive. That also covers the plain,
                    // single-level case, so one pair of buttons is enough.
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                val all = viewModel.filesRecursive()
                                if (all.isNotEmpty()) onPlayFolder(all.map { it.path }, 0)
                            }
                        }) {
                            Text("PLAY ALL IN FOLDER", style = Winamp.smallStyle, color = Winamp.Amber)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val all = viewModel.filesRecursive()
                                if (all.isNotEmpty()) onAddToQueue(all.map { it.path })
                            }
                        }) {
                            Text("ADD FOLDER TO QUEUE", style = Winamp.smallStyle, color = Winamp.Amber)
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Winamp.Background).bevel(inset = true)) {
                    when {
                        state.isLoading -> Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Winamp.Green, modifier = Modifier.size(20.dp))
                        }
                        state.error != null -> Text(
                            state.error!!,
                            style = Winamp.smallStyle,
                            color = Winamp.Amber,
                            modifier = Modifier.padding(16.dp),
                        )
                        state.entries.isEmpty() -> Text(
                            "EMPTY",
                            style = Winamp.smallStyle,
                            color = Winamp.GreenDim,
                            modifier = Modifier.padding(16.dp),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(state.entries) { entry ->
                                SmbEntryRow(
                                    entry = entry,
                                    onOpen = { viewModel.enter(entry) },
                                    onPlayFromHere = {
                                        val startIndex = files.indexOf(entry).coerceAtLeast(0)
                                        onPlayFolder(files.map { it.path }, startIndex)
                                    },
                                    onAddToQueue = { onAddToQueue(listOf(entry.path)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmbEntryRow(entry: SmbEntry, onOpen: () -> Unit, onPlayFromHere: () -> Unit, onAddToQueue: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (entry.isDirectory) onOpen() else onPlayFromHere() },
                    onLongClick = { if (!entry.isDirectory) menuExpanded = true },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (entry.isDirectory) Winamp.Amber else Winamp.Green,
                modifier = Modifier.size(20.dp),
            )
            Text(entry.name, style = Winamp.smallStyle, color = Winamp.Green, modifier = Modifier.padding(start = 10.dp))
            if (!entry.isDirectory) {
                // Extension only - cheap, no header read per row. The full technical picture (bit
                // depth, hi-res, Atmos) only gets read once a file is actually played; see the Now
                // Playing screen's technical line.
                Text(
                    entry.name.substringAfterLast('.', "").uppercase(),
                    style = Winamp.smallStyle,
                    color = Winamp.GreenDim,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (!entry.isDirectory) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Play folder from here") },
                    onClick = { menuExpanded = false; onPlayFromHere() },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = { menuExpanded = false; onAddToQueue() },
                )
            }
        }
    }
}
