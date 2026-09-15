package com.denonmusic.app.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.BuildConfig
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.app.update.UpdateCheckFrequency
import com.denonmusic.app.update.UpdateConfig
import com.denonmusic.app.update.UpdateManager
import com.denonmusic.app.update.UpdatePrefsStore
import com.denonmusic.app.update.UpdateScheduler
import com.denonmusic.app.update.UpdateUiState
import com.denonmusic.app.update.UpdateViewModel

/**
 * Newest first; the release workflow (`.github/workflows/release.yml`) parses this list to source
 * both the GitHub release notes and the in-app updater's "what's new" text for a version, and fails
 * a release with no matching entry - see `.github/workflows/README-release.md`.
 */
private val CHANGELOG = listOf(
    "0.1.2" to listOf(
        "Fixed a crash-on-launch loop when the last-visited browse folder became unreachable (server reindex, share rename, or a transient HEOS error) - the app now backs out to a working folder instead of crashing every time it reconnects.",
    ),
    "0.1.1" to listOf(
        "Settings can now discover the AVR on the LAN (SSDP) instead of typing its IP by hand.",
    ),
    "0.1.0" to listOf(
        "First tagged version: HEOS/AVR control, SMB+DLNA browsing with bridge-mode fallback playback, Now Playing with technical/Hi-Res info, and a password-protected LAN control HTTP API with its own browser GUI (Now Playing, Queue, AVR, Browse, Files).",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(updateViewModel: UpdateViewModel = hiltViewModel()) {
    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("A B O U T", style = Winamp.titleStyle) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Winamp.Background)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DENONMUSIC", style = Winamp.titleStyle, color = Winamp.Green)
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = Winamp.labelStyle,
                    color = Winamp.GreenDim,
                )
                Text("Built ${BuildConfig.BUILD_DATE}", style = Winamp.smallStyle)
            }

            Text(
                "A phone remote for a Denon AVR and its HEOS-networked speakers: transport, volume " +
                    "and sound-mode control, browsing and queueing a HEOS/DLNA library or a plain SMB " +
                    "share, and a bit-perfect-aware Now Playing view with technical/Hi-Res signal info.",
                style = Winamp.labelStyle,
            )

            SourceLinkButton()

            UpdateSection(updateViewModel)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("WHAT'S NEW")
                CHANGELOG.forEach { (version, changes) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Version $version", style = Winamp.labelStyle, color = Winamp.Amber)
                        changes.forEach { change ->
                            Text("•  $change", style = Winamp.smallStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLinkButton() {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val url = "https://github.com/${UpdateConfig.OWNER}/${UpdateConfig.REPO}"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("VIEW SOURCE / REPORT AN ISSUE", style = Winamp.labelStyle, color = Winamp.Amber)
    }
}

@Composable
private fun UpdateSection(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val store = remember { UpdatePrefsStore(context) }
    var frequency by remember { mutableStateOf(store.frequency) }

    Column(
        modifier = Modifier.fillMaxWidth().bevel().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("UPDATES")

        Text("Check for updates", style = Winamp.smallStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateCheckFrequency.entries.forEach { option ->
                FilterChip(
                    selected = frequency == option,
                    onClick = {
                        frequency = option
                        store.frequency = option
                        UpdateScheduler.schedule(context, option)
                    },
                    label = { Text(option.label, style = Winamp.smallStyle) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Winamp.Green,
                        selectedLabelColor = Winamp.Background,
                    ),
                )
            }
        }

        when (val s = state) {
            is UpdateUiState.Idle -> {
                Text("Check GitHub for a newer version.", style = Winamp.smallStyle)
                Button(onClick = viewModel::check) { Text("CHECK FOR UPDATES", style = Winamp.labelStyle) }
            }

            is UpdateUiState.Checking -> {
                Text("Checking…", style = Winamp.labelStyle, color = Winamp.Green)
            }

            is UpdateUiState.UpToDate -> {
                Text("You're on the latest version (${BuildConfig.VERSION_NAME}).", style = Winamp.labelStyle)
                OutlinedButton(onClick = viewModel::check) { Text("CHECK AGAIN", style = Winamp.labelStyle) }
            }

            is UpdateUiState.Available -> {
                Text(
                    "Version ${s.release.versionName} is available (you have ${BuildConfig.VERSION_NAME}).",
                    style = Winamp.labelStyle,
                    color = Winamp.Amber,
                )
                if (s.release.notes.isNotBlank()) {
                    Text(s.release.notes.trim(), style = Winamp.smallStyle)
                }
                val sizeText = if (s.release.apkSizeBytes > 0) " (${formatBytes(s.release.apkSizeBytes)})" else ""
                Button(onClick = { viewModel.download(s.release) }) {
                    Text("DOWNLOAD & INSTALL$sizeText", style = Winamp.labelStyle)
                }
            }

            is UpdateUiState.Downloading -> {
                val sizeText = if (s.sizeBytes > 0) " (${formatBytes(s.sizeBytes)})" else ""
                Text("Downloading… ${s.progress}%$sizeText", style = Winamp.labelStyle, color = Winamp.Green)
                LinearProgressIndicator(
                    progress = { s.progress / 100f },
                    color = Winamp.Green,
                    trackColor = Winamp.Panel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateUiState.ReadyToInstall -> {
                val sizeText = if (s.sizeBytes > 0) " (${formatBytes(s.sizeBytes)})" else ""
                Text("Downloaded version ${s.versionName}$sizeText.", style = Winamp.labelStyle, color = Winamp.Green)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (UpdateManager.canInstall(context)) {
                            UpdateManager.installApk(context, s.file)
                        } else {
                            UpdateManager.openInstallPermissionSettings(context)
                        }
                    }) { Text("INSTALL VERSION ${s.versionName}", style = Winamp.labelStyle) }
                }
                Text(
                    "If prompted, allow DenonMusic to install apps, then tap Install again.",
                    style = Winamp.smallStyle,
                )
            }

            is UpdateUiState.Error -> {
                Text(s.message, style = Winamp.labelStyle, color = Winamp.Amber)
                OutlinedButton(onClick = viewModel::check) { Text("TRY AGAIN", style = Winamp.labelStyle) }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Winamp.smallStyle, color = Winamp.Amber, modifier = Modifier.padding(bottom = 4.dp))
}
