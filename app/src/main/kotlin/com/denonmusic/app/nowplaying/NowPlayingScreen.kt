package com.denonmusic.app.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denonmusic.app.media.ChainIntegrity
import com.denonmusic.app.media.ChainIntegrityResult
import com.denonmusic.app.player.PlayerUiState
import com.denonmusic.app.player.PlayerViewModel
import com.denonmusic.app.player.icon
import com.denonmusic.app.player.next
import com.denonmusic.app.player.summary
import com.denonmusic.app.ui.LocalArtwork
import com.denonmusic.app.ui.RemoteArtwork
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.app.ui.fileTypeLabel
import com.denonmusic.heos.PlayState
import com.denonmusic.smb.AudioFormatInfo
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(playerViewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by playerViewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("N O W   P L A Y I N G", style = Winamp.titleStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Winamp.Green)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                    navigationIconContentColor = Winamp.Green,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Winamp.Background)
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.noHeosPlayerFound) {
                Box(modifier = Modifier.size(220.dp).bevel().padding(bottom = 16.dp))
                Text("NO HEOS PLAYER FOUND", style = Winamp.titleStyle, color = Winamp.Amber)
                Text(
                    "The receiver isn't reporting itself as a HEOS player right now - " +
                        "check it's powered on. A power cycle usually fixes this.",
                    style = Winamp.smallStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (state.isBridgeModeActive) {
                val item = state.bridgeQueue.currentItem
                Box(modifier = Modifier.size(220.dp).bevel().padding(bottom = 16.dp)) {
                    LocalArtwork(
                        bytes = state.bridgeQueue.currentArtwork,
                        contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(item?.displayName ?: "NO TRACK", style = Winamp.titleStyle, color = Winamp.Green)
                Text(
                    "SMB MODE - PROXIED VIA ANDROID APP",
                    style = Winamp.smallStyle,
                    color = Winamp.Amber,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    state.bridgeQueue.currentFormatInfo?.summary() ?: "reading format…",
                    style = Winamp.labelStyle,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Only meaningful for this (bridge) path: file-side info only exists here, and this
                // receiver reports PCM for all network-sourced audio regardless of the source file, so
                // the same comparison against a native HEOS/DLNA queue item would flag every DSD track
                // as a false "something transcoded it" - see ChainIntegrity's own doc.
                val integrity = ChainIntegrity.evaluate(
                    state.bridgeQueue.currentFormatInfo,
                    state.technicalInfo?.signalType,
                    state.technicalInfo?.sampleRateKhz,
                )
                if (integrity is ChainIntegrityResult.Mismatch) {
                    Text(integrity.description, style = Winamp.smallStyle, color = Winamp.Amber, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                val np = state.nowPlaying
                Box(modifier = Modifier.size(220.dp).bevel().padding(bottom = 16.dp)) {
                    RemoteArtwork(
                        url = np?.imageUrl,
                        contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    np?.song?.takeIf { it.isNotBlank() } ?: "NO TRACK",
                    style = Winamp.titleStyle,
                    color = Winamp.Green,
                )
                val subtitle = listOfNotNull(np?.artist?.takeIf { it.isNotBlank() }, np?.album?.takeIf { it.isNotBlank() })
                    .joinToString(" — ")
                Text(subtitle.ifEmpty { "—" }, style = Winamp.labelStyle, modifier = Modifier.padding(top = 8.dp))
                // fileTypeLabel is best-effort here: the HEOS-native SMB share reports the raw
                // filename (extension included) as `song`, but a DLNA/Plex-tagged title never carries
                // one - see [fileTypeLabel]'s own doc for why that's the right trade-off.
                val technicalLine = listOfNotNull(
                    np?.song?.let(::fileTypeLabel),
                    state.technicalInfo?.summary(),
                ).joinToString(" • ")
                if (technicalLine.isNotEmpty()) {
                    Text(technicalLine, style = Winamp.smallStyle, modifier = Modifier.padding(top = 4.dp))
                }
            }

            ProgressRow(state)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = playerViewModel::playPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Winamp.Green)
                }
                IconButton(onClick = playerViewModel::togglePlayPause, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(
                        if (state.playState == PlayState.Play) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/pause",
                        tint = Winamp.Green,
                        modifier = Modifier.height(40.dp),
                    )
                }
                IconButton(onClick = playerViewModel::playNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Winamp.Green)
                }
            }

            val effectiveRepeat = if (state.isBridgeModeActive) state.bridgeQueue.repeat else state.repeat
            val effectiveShuffle = if (state.isBridgeModeActive) state.bridgeQueue.shuffle else state.shuffle
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { playerViewModel.setRepeat(effectiveRepeat.next()) }) {
                    Icon(effectiveRepeat.icon(), contentDescription = "Repeat mode", tint = Winamp.Green)
                }
                IconButton(onClick = playerViewModel::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (effectiveShuffle == true) Winamp.Green else Winamp.GreenDim,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text("VOL", style = Winamp.smallStyle, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = (state.volume ?: 0).toFloat(),
                    onValueChange = { playerViewModel.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Winamp.Green,
                        activeTrackColor = Winamp.Green,
                        inactiveTrackColor = Winamp.BevelLight,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text("${state.volume ?: 0}", style = Winamp.smallStyle)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = playerViewModel::toggleMute) {
                    Icon(
                        if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Mute",
                        tint = if (state.muted) Winamp.Amber else Winamp.Green,
                    )
                }
                IconButton(onClick = playerViewModel::stop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Winamp.Green)
                }
                IconButton(onClick = playerViewModel::powerOff) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Power off", tint = Winamp.Amber)
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(state: PlayerUiState) {
    val progress = state.progress
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Box(modifier = Modifier.fillMaxWidth().bevel(inset = true).padding(2.dp)) {
            val fraction = if (progress.durationMillis > 0) {
                (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Winamp.Green,
                trackColor = Winamp.Panel,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress.positionMillis.toClock(), style = Winamp.smallStyle)
            Text(progress.durationMillis.toClock(), style = Winamp.smallStyle)
        }
    }
}

private fun Long.toClock(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/**
 * e.g. "FLAC 24-bit/192.0 kHz Stereo • HI-RES" or "MP4 (E-AC-3 (Atmos)) 48.0 kHz Stereo • ATMOS" for
 * the bridge mode technical line. [codecLabel] carries the real codec for an MP4/M4A wrapper, since
 * the container name alone ("MP4") says nothing about what's actually encoded inside it.
 */
private fun AudioFormatInfo.summary(): String = buildString {
    append(container.name.uppercase())
    codecLabel?.let { append(" ($it)") }
    append(' ')
    if (isDsd) {
        append("%.1f MHz".format(Locale.US, sampleRateHz / 1_000_000.0))
    } else {
        append("$bitsPerSample-bit/")
        append("%.1f kHz".format(Locale.US, sampleRateHz / 1_000.0))
    }
    append(' ')
    append(if (channels <= 2) if (channels == 1) "Mono" else "Stereo" else "${channels}ch")
    bitrateKbps?.let { append(" • $it kbps") }
    if (isAtmos) append(" • ATMOS")
    if (isHiRes) append(" • HI-RES")
}
