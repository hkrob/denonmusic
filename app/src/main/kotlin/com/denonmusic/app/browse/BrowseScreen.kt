package com.denonmusic.app.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.bridge.SmbBrowseViewModel
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.heos.BrowseItem
import com.denonmusic.smb.SmbEntry

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
                    if (state.isPlayableContainer) {
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

            if (state.isLoading && state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Winamp.Green)
                }
            } else if (state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOTHING HERE.", style = Winamp.labelStyle, color = Winamp.GreenDim)
                        BridgeModeTester(onPlayUrl = viewModel::playBridgeUrl, onPlaySmbPath = viewModel::playBridgeFromSmb)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items) { item ->
                        BrowseRow(item = item, onOpen = { viewModel.open(item) }, onAction = { viewModel.queue(item, it) })
                    }
                }
            }
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

@Composable
private fun Breadcrumb(names: List<String>, onClick: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Winamp.Panel).bevel().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(names.withIndex().toList()) { (index, name) ->
            Row {
                TextButton(onClick = { onClick(index) }) {
                    Text(name.uppercase(), style = Winamp.labelStyle, color = Winamp.Green)
                }
                if (index != names.lastIndex) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Winamp.GreenDim,
                        modifier = Modifier.align(Alignment.CenterVertically),
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
                Text(item.name, style = Winamp.labelStyle, color = Winamp.Green)
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
    smbBrowseViewModel: SmbBrowseViewModel = hiltViewModel(),
) {
    var browserOpen by remember { mutableStateOf(false) }
    var smbPath by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("DEGRADED BRIDGE MODE — no gapless, no DSD guarantee", style = Winamp.smallStyle, color = Winamp.Amber)

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
            SmbFileBrowser(viewModel = smbBrowseViewModel, onPlay = { entry -> onPlaySmbPath(entry.path) })
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
@Composable
private fun SmbFileBrowser(viewModel: SmbBrowseViewModel, onPlay: (SmbEntry) -> Unit) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (state.hasCredentials) {
            false -> Text("Set SMB host/share in Settings first.", style = Winamp.smallStyle, color = Winamp.Amber)
            null -> Box(modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(color = Winamp.Green, modifier = Modifier.size(20.dp))
            }
            true -> {
                val crumbs = listOf("ROOT") + state.pathSegments
                LazyRow(
                    modifier = Modifier.fillMaxWidth().background(Winamp.Panel).bevel().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(crumbs.withIndex().toList()) { (index, name) ->
                        Row {
                            TextButton(onClick = { viewModel.goToBreadcrumb(index - 1) }) {
                                Text(name.uppercase(), style = Winamp.smallStyle, color = Winamp.Green)
                            }
                            if (index != crumbs.lastIndex) {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = Winamp.GreenDim,
                                    modifier = Modifier.align(Alignment.CenterVertically).size(16.dp),
                                )
                            }
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
                                    onPlay = { onPlay(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmbEntryRow(entry: SmbEntry, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { if (entry.isDirectory) onOpen() else onPlay() })
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
    }
}
