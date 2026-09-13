package com.denonmusic.app.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.denonmusic.app.heos.HeosConnectionState
import com.denonmusic.app.ui.EqualizerBars
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.PlayState

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
        bottomBar = {
            if (state.avrHost != null) {
                NowPlayingBar(
                    state = state,
                    onTogglePlay = viewModel::togglePlayPause,
                    onVolumeChange = viewModel::setVolume,
                )
            }
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
                        BridgeModeTester(onPlay = viewModel::playBridgeUrl)
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
 * Debug-only affordance for exercising `browse/play_stream` (the plan's phase-6 degraded bridge
 * path) when no HEOS-indexed source has content yet. Not part of the primary browse flow.
 */
@Composable
private fun BridgeModeTester(onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("DEGRADED BRIDGE TEST — no gapless, no DSD guarantee", style = Winamp.smallStyle, color = Winamp.Amber)
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
        TextButton(onClick = { if (url.isNotBlank()) onPlay(url.trim()) }) {
            Text("PLAY STREAM", style = Winamp.labelStyle, color = Winamp.Amber)
        }
    }
}

@Composable
private fun NowPlayingBar(state: BrowseUiState, onTogglePlay: () -> Unit, onVolumeChange: (Int) -> Unit) {
    val np = state.nowPlaying
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Winamp.Panel)
            .bevel(inset = true)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (state.playState == PlayState.Play) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/pause",
                    tint = Winamp.Green,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = np?.song?.takeIf { it.isNotBlank() } ?: "NO TRACK",
                    style = Winamp.labelStyle,
                    color = Winamp.Green,
                )
                val subtitle = listOfNotNull(np?.artist?.takeIf { it.isNotBlank() }, np?.album?.takeIf { it.isNotBlank() })
                    .joinToString(" — ")
                Text(subtitle.ifEmpty { "—" }, style = Winamp.smallStyle)
            }
            EqualizerBars(
                levels = listOf(0.3f, 0.7f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f, 0.8f),
                modifier = Modifier.width(60.dp).height(28.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("VOL", style = Winamp.smallStyle, modifier = Modifier.width(32.dp))
            Slider(
                value = (state.volume ?: 0).toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Winamp.Green,
                    activeTrackColor = Winamp.Green,
                    inactiveTrackColor = Winamp.BevelLight,
                ),
                modifier = Modifier.weight(1f),
            )
            Text("${state.volume ?: 0}", style = Winamp.smallStyle, modifier = Modifier.width(28.dp))
        }
    }
}
