package com.denonmusic.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denonmusic.app.ui.EqualizerBars
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.heos.PlayState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

/**
 * Persistent strip above the tab content on every screen except Now Playing itself. Tapping it
 * (anywhere but the transport button) opens the full Now Playing screen - the same "tap to expand"
 * convention most music apps use for their mini player.
 */
@Composable
fun MiniPlayerBar(state: PlayerUiState, onTogglePlay: () -> Unit, onExpand: () -> Unit) {
    val np = state.nowPlaying
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Winamp.Panel)
            .bevel(inset = true)
            .clickable(onClick = onExpand)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
}
