package com.denonmusic.app.playlists

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.browse.QueueAction
import com.denonmusic.app.ui.Winamp
import com.denonmusic.heos.QueueTarget
import com.denonmusic.plex.PlexPlaylistItem

/**
 * Plex's own playlists, read straight from the Plex Media Server HTTP API - the receiver's HEOS/
 * DLNA browse tree has no notion of them at all (see `:core:plex`'s `PlexClient` doc). A playlist
 * opens into its resolved track list ([PlaylistRepository]); anything that didn't match the Search
 * index is shown dimmed rather than hidden, since "Plex has 14 tracks, HEOS can only see 11 of them"
 * is information worth keeping visible, not noise to filter out.
 *
 * Editing (add/remove/reorder, create/delete) is deliberately not here yet - this is the play-only
 * slice; see docs/local-setup.md for what the rest would need.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(viewModel: PlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Runs once per entry into composition - i.e. every time this tab is switched back to, not on
    // every recomposition - since navigating away disposes this composable even though the
    // ViewModel's own state survives it. See refreshSelectedIfOpen's own doc for why that matters.
    LaunchedEffect(Unit) { viewModel.refreshSelectedIfOpen() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val selected = state.selected
    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected == null) "P L A Y L I S T S" else selected.playlist.title.uppercase(),
                        style = Winamp.titleStyle,
                    )
                },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = viewModel::closePlaylist) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Winamp.Green)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Winamp.Panel, titleContentColor = Winamp.Green),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(Winamp.Background).padding(padding)) {
            when {
                state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Winamp.Green)
                }
                selected != null -> PlaylistDetail(selected, onPlayAll = viewModel::playAll, onQueueTrack = viewModel::queueTrack)
                state.playlists.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "NO PLEX PLAYLISTS FOUND.\nSET THE PLEX SERVER ADDRESS AND TOKEN IN SETTINGS.",
                        style = Winamp.labelStyle,
                        color = Winamp.GreenDim,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.playlists) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { viewModel.open(playlist) })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = Winamp.Amber)
                            Text(
                                "${playlist.title}  (${playlist.itemCount})",
                                modifier = Modifier.padding(start = 12.dp),
                                style = Winamp.labelStyle,
                                color = Winamp.Green,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    resolved: ResolvedPlaylist,
    onPlayAll: (QueueAction) -> Unit,
    onQueueTrack: (PlexPlaylistItem, QueueTarget, QueueAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Winamp.Panel).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val unmatched = resolved.tracks.count { it is ResolvedTrack.Unmatched }
            val ambiguous = resolved.tracks.count { it is ResolvedTrack.Ambiguous }
            // Named separately from "not in index": an ambiguous track IS in the index, twice over,
            // which is a different thing to tell the user than "nothing found" - conflating the two
            // would hide exactly the case PLAY ALL/ADD ALL most need flagged, since both silently
            // skip ambiguous tracks the same way they skip unmatched ones (see matchedTargets).
            val notes = buildList {
                if (unmatched > 0) add("$unmatched NOT IN HEOS INDEX")
                if (ambiguous > 0) add("$ambiguous AMBIGUOUS - LONG-PRESS TO PICK")
            }
            Text(
                if (notes.isEmpty()) "${resolved.tracks.size} TRACKS" else "${resolved.tracks.size} TRACKS, ${notes.joinToString(", ")}",
                style = Winamp.smallStyle,
                color = if (notes.isEmpty()) Winamp.GreenDim else Winamp.Amber,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onPlayAll(QueueAction.ReplaceAndPlay) }) {
                Text("PLAY ALL", style = Winamp.smallStyle, color = Winamp.Amber)
            }
            TextButton(onClick = { onPlayAll(QueueAction.AddToEnd) }) {
                Text("ADD ALL", style = Winamp.smallStyle, color = Winamp.Amber)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(resolved.tracks) { track -> PlaylistTrackRow(track, onQueueTrack) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(track: ResolvedTrack, onQueueTrack: (PlexPlaylistItem, QueueTarget, QueueAction) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    val matched = track as? ResolvedTrack.Matched
    val ambiguous = track as? ResolvedTrack.Ambiguous
    // A row is interactive (tap or long-press) only once resolution is unambiguous in some way:
    // Matched (one candidate - the normal case) or Ambiguous (several - long-press picks one).
    // Unmatched has nothing to queue at all, so it stays inert, same as it always has.
    val color = when (track) {
        is ResolvedTrack.Matched -> Winamp.Green
        is ResolvedTrack.Ambiguous -> Winamp.Amber
        is ResolvedTrack.Unmatched -> Winamp.GreenDim
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = matched != null || ambiguous != null,
                    onClick = { matched?.let { onQueueTrack(it.item, it.target, QueueAction.PlayNow) } },
                    onLongClick = {
                        if (matched != null || ambiguous != null) menuExpanded = true
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = color)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(track.item.title, style = Winamp.labelStyle, color = color)
                val subtitle = listOfNotNull(track.item.artist, track.item.album).joinToString(" — ")
                if (subtitle.isNotEmpty()) Text(subtitle, style = Winamp.smallStyle)
            }
        }
        if (matched != null) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                QueueAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            menuExpanded = false
                            onQueueTrack(matched.item, matched.target, action)
                        },
                    )
                }
            }
        }
        if (ambiguous != null) {
            // No per-candidate label to show (a track row's indexed path isn't kept - see
            // SearchIndexEntity's own doc), so these are only numbered, not described. Picking one
            // plays it immediately rather than opening a second QueueAction menu per candidate - the
            // point here is "let me hear which one this is", not the full menu a confident match gets.
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                ambiguous.candidates.forEachIndexed { index, candidate ->
                    DropdownMenuItem(
                        text = { Text("Play version ${index + 1} of ${ambiguous.candidates.size}") },
                        onClick = {
                            menuExpanded = false
                            onQueueTrack(ambiguous.item, candidate, QueueAction.PlayNow)
                        },
                    )
                }
            }
        }
    }
}
