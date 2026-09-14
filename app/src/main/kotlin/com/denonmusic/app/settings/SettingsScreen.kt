package com.denonmusic.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.denonmusic.app.lancontrol.LanControlStatus
import com.denonmusic.app.ui.Winamp
import com.denonmusic.avr.DiscoveredAvr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.settingsState.collectAsState()

    var avrHost by remember { mutableStateOf("") }
    var inputMnemonic by remember { mutableStateOf("") }
    var smbHost by remember { mutableStateOf("") }
    var smbShare by remember { mutableStateOf("") }
    var smbUsername by remember { mutableStateOf("") }
    var smbPassword by remember { mutableStateOf("") }
    var autoDimAfterSeconds by remember { mutableStateOf("") }
    var autoDimBrightnessPercent by remember { mutableStateOf("") }
    var lanControlPassword by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        avrHost = state.avrHost.orEmpty()
        inputMnemonic = state.avrInputMnemonic
        smbHost = state.smbHost.orEmpty()
        smbShare = state.smbShare.orEmpty()
        smbUsername = state.smbUsername.orEmpty()
        smbPassword = state.smbPassword.orEmpty()
        autoDimAfterSeconds = state.autoDimAfterSeconds.toString()
        autoDimBrightnessPercent = state.autoDimBrightnessPercent.toString()
        lanControlPassword = state.lanControlPassword.orEmpty()
    }

    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("S E T T I N G S", style = Winamp.titleStyle) },
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
        ) {
            SectionLabel("RECEIVER")
            LabeledField("AVR / HEOS host", avrHost, { avrHost = it })
            LabeledField("Input mnemonic (e.g. NET)", inputMnemonic, { inputMnemonic = it })
            TextButton(onClick = {
                if (avrHost.isNotBlank()) viewModel.setAvrHost(avrHost.trim())
                if (inputMnemonic.isNotBlank()) viewModel.setAvrInputMnemonic(inputMnemonic.trim())
            }) {
                Text("SAVE RECEIVER SETTINGS", style = Winamp.labelStyle, color = Winamp.Amber)
            }
            AvrDiscoverySection(viewModel)

            SectionLabel("SMB SHARE (for technical detail + artwork overlay)", modifier = Modifier.padding(top = 24.dp))
            LabeledField("Host", smbHost, { smbHost = it })
            LabeledField("Share name", smbShare, { smbShare = it })
            LabeledField("Username", smbUsername, { smbUsername = it })
            LabeledField("Password", smbPassword, { smbPassword = it }, isPassword = true)
            TextButton(onClick = {
                viewModel.setSmbCredentials(smbHost.trim(), smbShare.trim(), smbUsername.trim(), smbPassword)
            }) {
                Text("SAVE SMB CREDENTIALS", style = Winamp.labelStyle, color = Winamp.Amber)
            }

            SmbConnectionTester(viewModel)
            SmbTester(viewModel)

            SectionLabel("DISPLAY", modifier = Modifier.padding(top = 24.dp))
            SwitchRow(
                label = "Keep screen on",
                checked = state.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
            )

            SwitchRow(
                label = "Auto-dim when idle",
                checked = state.autoDimEnabled,
                onCheckedChange = {
                    viewModel.setAutoDim(
                        it,
                        autoDimAfterSeconds.toIntOrNull() ?: state.autoDimAfterSeconds,
                        autoDimBrightnessPercent.toIntOrNull() ?: state.autoDimBrightnessPercent,
                    )
                },
            )
            LabeledField("Dim after (seconds)", autoDimAfterSeconds, { autoDimAfterSeconds = it })
            LabeledField("Dim to (% brightness, 1-100)", autoDimBrightnessPercent, { autoDimBrightnessPercent = it })
            TextButton(onClick = {
                val afterSeconds = autoDimAfterSeconds.toIntOrNull() ?: state.autoDimAfterSeconds
                val brightness = autoDimBrightnessPercent.toIntOrNull() ?: state.autoDimBrightnessPercent
                viewModel.setAutoDim(state.autoDimEnabled, afterSeconds, brightness)
            }) {
                Text("SAVE AUTO-DIM SETTINGS", style = Winamp.labelStyle, color = Winamp.Amber)
            }

            SectionLabel("LAN CONTROL", modifier = Modifier.padding(top = 24.dp))
            Text(
                "Lets another device on the LAN drive playback over HTTP. A blank password keeps this " +
                    "off no matter what the switch below says.",
                style = Winamp.smallStyle,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SwitchRow(
                label = "Enable LAN control",
                checked = state.lanControlEnabled,
                onCheckedChange = { viewModel.setLanControl(it, lanControlPassword) },
            )
            LabeledField("Password (required)", lanControlPassword, { lanControlPassword = it }, isPassword = true)
            TextButton(onClick = { viewModel.setLanControl(state.lanControlEnabled, lanControlPassword) }) {
                Text("SAVE LAN CONTROL SETTINGS", style = Winamp.labelStyle, color = Winamp.Amber)
            }
            LanControlStatusRow(viewModel)
        }
    }
}

/**
 * SSDP M-SEARCH for the receiver on the LAN - manual entry into the field above stays the fallback
 * for a receiver that doesn't answer (different subnet, SSDP blocked by the router, etc).
 */
@Composable
private fun AvrDiscoverySection(viewModel: SettingsViewModel) {
    val state by viewModel.avrDiscovery.collectAsState()

    Column(modifier = Modifier.padding(top = 4.dp)) {
        TextButton(
            onClick = viewModel::discoverAvr,
            enabled = state !is AvrDiscoveryUiState.Searching,
        ) {
            Text("DISCOVER ON LAN", style = Winamp.labelStyle, color = Winamp.Amber)
        }
        when (val s = state) {
            is AvrDiscoveryUiState.Searching -> Text("Searching...", style = Winamp.smallStyle)
            is AvrDiscoveryUiState.NotFound -> Text(
                "No receiver answered - enter the IP manually above.",
                style = Winamp.smallStyle,
            )
            is AvrDiscoveryUiState.Found -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.results.forEach { found ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(found.host, style = Winamp.labelStyle, color = Winamp.Green)
                            Text(found.label, style = Winamp.smallStyle)
                        }
                        TextButton(onClick = { viewModel.useDiscoveredAvr(found.host) }) {
                            Text("USE", style = Winamp.labelStyle, color = Winamp.Amber)
                        }
                    }
                }
            }
            is AvrDiscoveryUiState.Idle -> {}
        }
    }
}

@Composable
private fun LanControlStatusRow(viewModel: SettingsViewModel) {
    val status by viewModel.lanControlStatus.collectAsState()
    val text = when (val s = status) {
        is LanControlStatus.Off -> "OFF"
        is LanControlStatus.Running -> "RUNNING - ${s.url} (send the password as header X-Lan-Control-Token or ?token=)"
        is LanControlStatus.Error -> "ERROR - ${s.reason}"
    }
    val color = when (status) {
        is LanControlStatus.Running -> Winamp.Green
        is LanControlStatus.Error -> Winamp.Amber
        else -> Winamp.GreenDim
    }
    Text(text, style = Winamp.smallStyle, color = color, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Winamp.labelStyle, color = Winamp.Green, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // On a real device, the original green-thumb-on-both-states pairing (GreenDim vs Green
            // thumb, Panel vs PanelLight track - both dark, both green) read as visually identical at
            // a glance, thumb position aside. Grey unchecked / green-tinted checked track reads
            // clearly as off vs. on instead.
            colors = SwitchDefaults.colors(
                checkedThumbColor = Winamp.Green,
                checkedTrackColor = Winamp.GreenDim,
                checkedBorderColor = Winamp.Green,
                uncheckedThumbColor = Winamp.BevelLight,
                uncheckedTrackColor = Winamp.Background,
                uncheckedBorderColor = Winamp.BevelLight,
            ),
        )
    }
}

/**
 * "Can we actually log in and see the share" - complements [SmbTester] below, which needs a real
 * file path and tests header parsing rather than connectivity. Lists the share root, so it works
 * before you know any file path at all.
 */
@Composable
private fun SmbConnectionTester(viewModel: SettingsViewModel) {
    val result by viewModel.smbConnectionTestResult.collectAsState()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = viewModel::testSmbConnection) {
            Text("TEST SMB CONNECTION", style = Winamp.labelStyle, color = Winamp.Amber)
        }
        result?.let { Text(it, style = Winamp.smallStyle, color = Winamp.Green) }
    }
}

/**
 * Debug-only affordance for exercising the real SMB network path end to end against a saved
 * host/share - the header parsers are unit-tested against fixtures, but jcifs-ng's own network
 * behaviour has never touched a real share (see docs/local-setup.md). Same "opt-in, clearly
 * labelled" spirit as the Browse screen's degraded-bridge tester.
 */
@Composable
private fun SmbTester(viewModel: SettingsViewModel) {
    var path by remember { mutableStateOf("") }
    val result by viewModel.smbTestResult.collectAsState()

    Column(modifier = Modifier.padding(top = 24.dp)) {
        SectionLabel("SMB PARSE TEST (debug)")
        LabeledField("File path within share, e.g. Artist/Album/track.flac", path, { path = it })
        TextButton(onClick = { if (path.isNotBlank()) viewModel.testSmbPath(path.trim()) }) {
            Text("PARSE", style = Winamp.labelStyle, color = Winamp.Amber)
        }
        result?.let { Text(it, style = Winamp.smallStyle, color = Winamp.Green) }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = Winamp.smallStyle)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = Winamp.labelStyle.copy(color = Winamp.Green),
            visualTransformation = if (isPassword) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Winamp.Green,
                unfocusedBorderColor = Winamp.BevelLight,
                cursorColor = Winamp.Green,
            ),
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Winamp.smallStyle, color = Winamp.Amber, modifier = modifier.padding(bottom = 4.dp))
}
