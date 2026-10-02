package com.denonmusic.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whole-library search over a client-side index (see [SearchIndexRepository] for why this is
 * client-side at all: the receiver's own `browse/search` is broken on the real AVR-X4500H).
 *
 * [onOpenInBrowse] switches the main tab row to Browse after [SearchViewModel.openContainer] has
 * pushed the result's path onto the shared stack - this screen doesn't own a reference to the
 * [androidx.navigation.NavController] that does that, by design, so the caller (`MainScreen`) wires
 * the two together.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(onOpenInBrowse: () -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
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
                title = { Text("S E A R C H", style = Winamp.titleStyle) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(Winamp.Background).padding(padding)) {
            IndexBanner(state.indexStatus, onReindex = viewModel::reindex)

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Artist, album or track", style = Winamp.smallStyle) },
                textStyle = Winamp.labelStyle.copy(color = Winamp.Green),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Winamp.Green,
                    unfocusedBorderColor = Winamp.BevelLight,
                    cursorColor = Winamp.Green,
                ),
            )

            when {
                state.isSearching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Winamp.Green)
                }
                state.query.isBlank() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.indexStatus is SearchIndexStatus.NeverIndexed) {
                            // A full crawl over a real library measured in the tens of minutes, not
                            // seconds (see docs/local-setup.md, 2026-10-02) - said plainly up front
                            // rather than left to look stuck once "BUILD INDEX" is tapped.
                            "BUILD THE INDEX ABOVE, THEN SEARCH.\nA FIRST BUILD CAN TAKE A WHILE - " +
                                "IT KEEPS RUNNING IF YOU LEAVE THIS SCREEN."
                        } else {
                            "TYPE TO SEARCH THE INDEXED LIBRARY."
                        },
                        style = Winamp.labelStyle,
                        color = Winamp.GreenDim,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                state.results.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("NO MATCHES.", style = Winamp.labelStyle, color = Winamp.GreenDim)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.results) { result ->
                        SearchResultRow(
                            result = result,
                            onOpen = {
                                viewModel.openContainer(result)
                                onOpenInBrowse()
                            },
                            onAction = { viewModel.queue(result, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexBanner(status: SearchIndexStatus, onReindex: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Winamp.Panel).bevel().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when (status) {
            is SearchIndexStatus.NeverIndexed -> "LIBRARY NOT YET INDEXED"
            is SearchIndexStatus.Indexing -> "INDEXING... ${status.found} FOUND"
            is SearchIndexStatus.Ready -> "${status.count} ITEMS - INDEXED ${status.indexedAt.toRelativeLabel()}"
            is SearchIndexStatus.Failed -> "INDEX FAILED: ${status.message}"
        }
        Text(label, style = Winamp.smallStyle, color = if (status is SearchIndexStatus.Failed) Winamp.Amber else Winamp.GreenDim)
        if (status !is SearchIndexStatus.Indexing) {
            TextButton(onClick = onReindex) {
                Text(
                    if (status is SearchIndexStatus.NeverIndexed) "BUILD INDEX" else "REINDEX",
                    style = Winamp.smallStyle,
                    color = Winamp.Amber,
                )
            }
        }
    }
}

private fun Long.toRelativeLabel(): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(this)).uppercase(Locale.US)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(result: SearchResult, onOpen: () -> Unit, onAction: (QueueAction) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (result.isContainer) onOpen() else onAction(QueueAction.PlayNow) },
                    onLongClick = { menuExpanded = true },
                )
                .background(Winamp.Background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                if (result.isContainer) Icons.Filled.Folder else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (result.isContainer) Winamp.Amber else Winamp.Green,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(result.name, style = Winamp.labelStyle, color = Winamp.Green)
                val subtitle = listOfNotNull(result.artist, result.album).joinToString(" — ")
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
