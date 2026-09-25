package com.denonmusic.app.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import java.util.Locale

/**
 * Newest first; the release workflow (`.github/workflows/release.yml`) parses this list to source
 * both the GitHub release notes and the in-app updater's "what's new" text for a version, and fails
 * a release with no matching entry - see `.github/workflows/README-release.md`.
 */
private val CHANGELOG = listOf(
    "0.1.12" to listOf(
        "Added a notification with play/pause, next and stop, showing the current track, artist " +
            "and album art - so the receiver can be controlled without opening the app. It clears " +
            "itself when the app is closed, because the app is what keeps it honest about what " +
            "the receiver is doing; the music carries on regardless.",
    ),
    "0.1.11" to listOf(
        "Fixed \"Play all\" on a multi-disc album queueing the tracks but never starting them, " +
            "and adding to whatever was already queued instead of replacing it. The receiver " +
            "carries out \"replace and play\" in its own time, and a second disc sent straight " +
            "after it threw away both the replace and the play.",
    ),
    "0.1.10" to listOf(
        "Fixed the update check itself failing with a parser error, which is why 0.1.8 and 0.1.9 " +
            "could not update themselves and had to be installed by hand. The hardening added in " +
            "0.1.8 called an XML setting that Android rejects outright rather than ignoring.",
    ),
    "0.1.9" to listOf(
        "Fixed \"Play all\" and \"Add all to queue\" failing with \"Out of range\" on multi-disc " +
            "albums and on folders holding an extras subfolder. The receiver accepts a queue add " +
            "before it has finished the previous one, and refuses anything sent in between; the " +
            "app now waits for it instead of giving up.",
        "Folders too big to queue at once are now refused straight away, instead of after a " +
            "minutes-long scan that was always going to end in the same message.",
    ),
    "0.1.8" to listOf(
        "Fixed the queue's move-down button doing nothing on the second-to-last track - the one " +
            "row whose move has no following track to land in front of, and so has to go to the end.",
        "Fixed Browse showing tracks that are no longer there: a folder that shrank kept its " +
            "removed rows in the offline cache indefinitely, and that cache never expired or pruned.",
        "Fixed the app reporting itself connected to the receiver for up to 30 seconds after the " +
            "connection had actually dropped - which also stopped it reconnecting on its own.",
        "Fixed a leak of network connections on every reconnect, and unbounded memory growth " +
            "during long bridge (SMB) playback sessions.",
        "Auto-dim no longer keeps a once-a-second timer running while the app is in the " +
            "background, and returning to the app no longer dims the screen immediately.",
        "Fixed the LAN control web UI's scan progress counting backwards through nested folders, " +
            "and two simultaneous scans blanking each other's progress line.",
        "Fixed the direct-AVR volume commands - not the app's own volume slider, which goes " +
            "through HEOS: a half-dB step below -70 dB set a far louder level than asked for, and " +
            "reading the volume back could come out empty.",
        "Fixed the release notes shown here and on GitHub being chopped into fragments " +
            "mid-sentence, as 0.1.6 and 0.1.7 above still are - the generator broke each bullet " +
            "apart at every line it was wrapped across.",
    ),
    "0.1.7" to listOf(
        "Moved input select from Settings to the AVR tab, and made it actually switch the " +
            "receiver's input live - it previously only saved a setting despite looking like a " +
            "working control.",
    ),
    "0.1.6" to listOf(
        "Added a receiver reboot action (AVR panel and the LAN control API's new POST /reboot) - " +
            "recovers the receiver's HEOS network module without a manual power-cycle when browse, " +
            "now-playing or the queue get wedged and won't come back on their own.",
    ),
    "0.1.5" to listOf(
        "Fixed the update checker failing with \"Couldn't reach GitHub\" even when a new version was published - it was hitting GitHub's shared API rate limit, which a busy NAT/CGNAT connection can exhaust on its own.",
        "Fixed the Browse A-Z jump index crushing the folder list down to nothing on any DLNA/Plex folder long enough to show it, making Browse unusable on those sources.",
    ),
    "0.1.4" to listOf(
        "Security: the app no longer allows adb backup, which could previously lift the stored SMB and LAN control credentials in plaintext.",
        "Fixed a false \"something resampled it\" warning on genuine 192 kHz FLAC played through the bridge path, caused by misreading the receiver's reported sample rate for the 48 kHz-derived family.",
        "Fixed Now Playing and transport controls continuing to follow a finished bridge (SMB) track after tapping a row in the real HEOS queue.",
        "Fixed the LAN control API's browse source getting stuck after a share rename or HEOS reindex, instead of recovering like the in-app browser already did.",
        "Extended the HEOS-player-list recovery added in 0.1.3 to the LAN control API and bridge queue, which each kept their own stale player reference and could still wedge until the app was restarted.",
        "Added an A-Z jump index to Browse for long folders, since the receiver has no server-side search on this source.",
        "Added quick-pick buttons for the common AVR input mnemonics in Settings, instead of typing them by hand.",
    ),
    "0.1.3" to listOf(
        "Fixed Now Playing showing a stale or wrong track after a bridge-mode (SMB) queue finished or was replaced from the LAN control API.",
        "Fixed \"Nothing playable found\" on Play All for a folder containing both tracks and subfolders.",
        "The app now recovers automatically if the receiver's HEOS player list goes empty, instead of getting stuck.",
        "Track titles with an ampersand from a DLNA/Plex source no longer show up mangled (e.g. \"Girls &amp; Boys\" instead of \"Girls & Boys\").",
        "Added the NeuralX surround mode, fixed a chip label wrapping oddly on the About screen, and added a visible GitHub link on the About screen.",
    ),
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
                GitHubLink()
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

private val GITHUB_URL = "https://github.com/${UpdateConfig.OWNER}/${UpdateConfig.REPO}"

/** Plain visible URL text, distinct from [SourceLinkButton]'s action-labelled button below it. */
@Composable
private fun GitHubLink() {
    val context = LocalContext.current
    Text(
        "GitHub: github.com/${UpdateConfig.OWNER}/${UpdateConfig.REPO}",
        style = Winamp.smallStyle,
        color = Winamp.Amber,
        modifier = Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        },
    )
}

@Composable
private fun SourceLinkButton() {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("VIEW SOURCE / REPORT AN ISSUE", style = Winamp.labelStyle, color = Winamp.Amber)
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

// Locale.US, matching TechnicalInfo.summary()'s own formatting: without it a German or French
// device renders a download size as "1,5 GB", which reads as a thousands separator here.
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Winamp.smallStyle, color = Winamp.Amber, modifier = Modifier.padding(bottom = 4.dp))
}
