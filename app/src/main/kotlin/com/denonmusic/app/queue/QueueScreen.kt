package com.denonmusic.app.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import com.denonmusic.app.player.PlayerViewModel
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.heos.QueueItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(playerViewModel: PlayerViewModel) {
    val state by playerViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            playerViewModel.dismissMessage()
        }
    }

    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("Q U E U E", style = Winamp.titleStyle) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                ),
                actions = {
                    TextButton(onClick = { showSaveDialog = true }, enabled = state.queue.isNotEmpty()) {
                        Text("SAVE", style = Winamp.labelStyle, color = Winamp.Amber)
                    }
                    TextButton(onClick = playerViewModel::clearQueue, enabled = state.queue.isNotEmpty()) {
                        Text("CLEAR", style = Winamp.labelStyle, color = Winamp.Amber)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.queue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().background(Winamp.Background).padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("QUEUE IS EMPTY", style = Winamp.labelStyle, color = Winamp.GreenDim)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().background(Winamp.Background).padding(padding)) {
                itemsIndexed(state.queue) { index, item ->
                    QueueRow(
                        item = item,
                        onTap = { playerViewModel.playQueueItem(item.qid) },
                        onRemove = { playerViewModel.removeFromQueue(item.qid) },
                        onMoveUp = { if (index > 0) playerViewModel.moveQueueItem(item.qid, state.queue[index - 1].qid) },
                        onMoveDown = {
                            if (index < state.queue.lastIndex - 1) {
                                playerViewModel.moveQueueItem(item.qid, state.queue[index + 2].qid)
                            }
                        },
                        canMoveUp = index > 0,
                        canMoveDown = index < state.queue.lastIndex,
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePlaylistDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                playerViewModel.saveQueueAsPlaylist(name)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                onRemove()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().background(Winamp.Amber).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Winamp.Background)
            }
        },
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Winamp.Background)
                    .combinedClickable(onClick = onTap, onLongClick = { menuExpanded = true })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Winamp.Green)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.song, style = Winamp.labelStyle, color = Winamp.Green)
                    val subtitle = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" — ")
                    if (subtitle.isNotEmpty()) Text(subtitle, style = Winamp.smallStyle)
                }
                IconButton(onClick = { menuExpanded = true }) {
                    Text("⋮", style = Winamp.labelStyle, color = Winamp.GreenDim)
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Play now") },
                    onClick = { menuExpanded = false; onTap() },
                )
                DropdownMenuItem(
                    text = { Text("Move up") },
                    enabled = canMoveUp,
                    onClick = { menuExpanded = false; onMoveUp() },
                )
                DropdownMenuItem(
                    text = { Text("Move down") },
                    enabled = canMoveDown,
                    onClick = { menuExpanded = false; onMoveDown() },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = { menuExpanded = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun SavePlaylistDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist", style = Winamp.labelStyle) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) {
                Text("SAVE", color = Winamp.Amber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}
