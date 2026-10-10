package com.denonmusic.app.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denonmusic.app.ui.Winamp
import com.denonmusic.plex.PlexPlaylist

/**
 * "Which playlist?" - shown from Search, Browse and Playlists' own track rows when adding a single
 * track, and shared rather than copied three times since the three call sites need nothing track- or
 * screen-specific out of it: a list of [playlists], a pick, or a dismiss.
 *
 * [playlists] empty while still loading is intentionally indistinguishable from empty because the
 * user genuinely has none - this dialog only ever opens from an action that already knows there is
 * something to add, so the caller is responsible for not opening it before the real list has had a
 * chance to arrive; see each call site's own ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerDialog(playlists: List<PlexPlaylist>, isLoading: Boolean, onPick: (PlexPlaylist) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Plex playlist", style = Winamp.labelStyle, color = Winamp.Green) },
        containerColor = Winamp.Panel,
        text = {
            when {
                isLoading -> CircularProgressIndicator(color = Winamp.Green)
                playlists.isEmpty() -> Text("No Plex playlists found.", style = Winamp.smallStyle, color = Winamp.GreenDim)
                else -> LazyColumn {
                    items(playlists) { playlist ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist) }
                                .background(Winamp.Background)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Text("${playlist.title}  (${playlist.itemCount})", style = Winamp.labelStyle, color = Winamp.Green)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", style = Winamp.labelStyle, color = Winamp.Amber)
            }
        },
    )
}
