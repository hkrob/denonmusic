package com.denonmusic.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.ui.Winamp

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

    LaunchedEffect(state) {
        avrHost = state.avrHost.orEmpty()
        inputMnemonic = state.avrInputMnemonic
        smbHost = state.smbHost.orEmpty()
        smbShare = state.smbShare.orEmpty()
        smbUsername = state.smbUsername.orEmpty()
        smbPassword = state.smbPassword.orEmpty()
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
        }
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
