package com.denonmusic.app.avr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.denonmusic.app.player.TechnicalInfo
import com.denonmusic.app.player.summary
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.bevel
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.avr.PowerState
import com.denonmusic.avr.SoundMode

/** Layout order for the OUTPUT map grid, roughly matching a real speaker plan front-to-back. */
private val ALL_CHANNELS = listOf("FL", "C", "FR", "SL", "SR", "SBL", "SBR", "SW", "SW2")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvrScreen(viewModel: AvrViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Winamp.Background,
        topBar = {
            TopAppBar(
                title = { Text("A V R", style = Winamp.titleStyle) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Winamp.Panel,
                    titleContentColor = Winamp.Green,
                ),
                actions = {
                    IconButton(onClick = viewModel::togglePower) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = if (state.powerState == PowerState.On) Winamp.Green else Winamp.GreenDim,
                        )
                    }
                },
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
            SectionLabel("INPUT")
            Text(
                state.inputSource?.let { "SOURCE: $it" } ?: "UNKNOWN",
                style = Winamp.labelStyle,
                color = Winamp.Green,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            SectionLabel("SOUND MODE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            ) {
                SoundMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.soundMode == mode,
                        onClick = { viewModel.setSoundMode(mode) },
                        label = { Text(mode.name, style = Winamp.smallStyle) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Winamp.Green,
                            selectedLabelColor = Winamp.Background,
                        ),
                    )
                }
            }

            SectionLabel("BIT-PERFECT POLICY")
            Text(
                "Applied automatically whenever playback starts. Pure Direct also disables the " +
                    "front display, video circuitry, tone controls and Audyssey.",
                style = Winamp.smallStyle,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                BitPerfectPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = state.bitPerfectPolicy == policy,
                        onClick = { viewModel.setBitPerfectPolicy(policy) },
                        label = { Text(policy.name, style = Winamp.smallStyle) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Winamp.Amber,
                            selectedLabelColor = Winamp.Background,
                        ),
                    )
                }
            }

            SectionLabel("OUTPUT MAP")
            val active = state.outputChannels.map { it.code }.toSet()
            // A plain chunked grid, not LazyVerticalGrid: nine tiles never need laziness, and a lazy
            // grid can't be nested inside this screen's own verticalScroll (it measures its scroll
            // axis with an infinite constraint, which a lazy layout can't size against). Kept small -
            // this is a status glance, not the focal point of the screen.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.6f),
            ) {
                ALL_CHANNELS.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { code ->
                            val isActive = code in active
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(2.2f)
                                    .background(if (isActive) Winamp.Green else Winamp.Panel)
                                    .bevel(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    code,
                                    style = Winamp.smallStyle,
                                    color = if (isActive) Winamp.Background else Winamp.GreenDim,
                                )
                            }
                        }
                    }
                }
            }

            // Same [TechnicalInfo.summary] the Now Playing technical line renders, built from this
            // screen's own already-live signal/sample-rate/output-channel state, so the two never show
            // different detail for the same receiver.
            val technicalInfo = TechnicalInfo(
                signalType = state.signalType,
                sampleRateKhz = state.sampleRateKhz,
                activeOutputChannels = state.outputChannels.size,
            )
            technicalInfo.summary()?.let { summary ->
                SectionLabel("SIGNAL", modifier = Modifier.padding(top = 16.dp))
                Text(summary, style = Winamp.labelStyle, color = Winamp.Green)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Winamp.smallStyle, color = Winamp.Amber, modifier = modifier.padding(bottom = 4.dp))
}
