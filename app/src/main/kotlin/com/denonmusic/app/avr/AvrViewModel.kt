package com.denonmusic.app.avr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.avr.AvrClient
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.avr.OutputChannel
import com.denonmusic.avr.PowerState
import com.denonmusic.avr.SignalType
import com.denonmusic.avr.SoundMode
import com.denonmusic.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AvrUiState(
    val connection: AvrConnectionState = AvrConnectionState.Disconnected,
    val powerState: PowerState? = null,
    val inputSource: String? = null,
    val soundMode: SoundMode? = null,
    val signalType: SignalType? = null,
    val sampleRateKhz: Double? = null,
    val outputChannels: List<OutputChannel> = emptyList(),
    val bitPerfectPolicy: BitPerfectPolicy = BitPerfectPolicy.Off,
    val inputMnemonic: String = "NET",
)

@HiltViewModel
class AvrViewModel @Inject constructor(
    private val session: AvrSession,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvrUiState())
    val uiState: StateFlow<AvrUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settings.settings.first()
            _uiState.value = _uiState.value.copy(
                bitPerfectPolicy = saved.bitPerfectPolicy?.let { runCatching { BitPerfectPolicy.valueOf(it) }.getOrNull() }
                    ?: BitPerfectPolicy.Off,
                inputMnemonic = saved.avrInputMnemonic,
            )
            saved.avrHost?.let { session.start(it) }
        }
        viewModelScope.launch {
            session.state.collectLatest { state ->
                _uiState.value = _uiState.value.copy(connection = state)
                if (state is AvrConnectionState.Connected) {
                    refresh()
                    subscribeToEvents()
                }
            }
        }
    }

    fun refresh() {
        val client = session.avrClient ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                powerState = runCatching { client.powerState() }.getOrNull(),
                inputSource = runCatching { client.inputSource() }.getOrNull(),
                soundMode = runCatching { client.soundMode() }.getOrNull(),
                signalType = runCatching { client.signalType() }.getOrNull(),
                sampleRateKhz = runCatching { client.sampleRateKhz() }.getOrNull(),
                outputChannels = runCatching { client.outputChannels() }.getOrDefault(emptyList()),
            )
        }
    }

    /**
     * The channels seen so far in the output-map block currently being read off the live event
     * stream, reset on each `CVEND`. Kept off the UI state so a burst in progress doesn't flash a
     * partial map - see [subscribeToEvents].
     */
    private val liveOutputChannels = mutableListOf<OutputChannel>()

    private fun subscribeToEvents() {
        eventsJob?.cancel()
        val events = session.events ?: return
        eventsJob = viewModelScope.launch {
            events.collect { line ->
                PowerState.fromWire(line)?.let { power ->
                    _uiState.value = _uiState.value.copy(powerState = power)
                }
                SoundMode.fromWire(line)?.let { mode ->
                    _uiState.value = _uiState.value.copy(soundMode = mode)
                }
                if (line.startsWith("SI")) {
                    _uiState.value = _uiState.value.copy(inputSource = line.removePrefix("SI"))
                }
                // The AVR-X4500H probed for this project free-runs this exact CV.../CVEND shape
                // unprompted roughly once a second (confirmed by raw probe - see docs/local-setup.md).
                // Reading it passively off the event stream, rather than re-sending `CV?` in response
                // to seeing one arrive, is what keeps the OUTPUT map live without a query racing the
                // receiver's own broadcast and clobbering a good result with a half-read one.
                when {
                    line.startsWith("CVEND") -> {
                        _uiState.value = _uiState.value.copy(outputChannels = liveOutputChannels.toList())
                        liveOutputChannels.clear()
                    }
                    line.startsWith("CV") -> liveOutputChannels += AvrClient.parseChannelLine(line)
                }
            }
        }
    }

    fun togglePower() {
        val client = session.avrClient ?: return
        val next = if (_uiState.value.powerState == PowerState.On) PowerState.Standby else PowerState.On
        viewModelScope.launch {
            runCatching { if (next == PowerState.On) client.powerOn() else client.powerStandby() }
                .onSuccess { _uiState.value = _uiState.value.copy(powerState = next) }
        }
    }

    fun selectInput(mnemonic: String) {
        val client = session.avrClient ?: return
        viewModelScope.launch {
            runCatching { client.selectInput(mnemonic) }
                .onSuccess { _uiState.value = _uiState.value.copy(inputSource = mnemonic) }
        }
    }

    fun setSoundMode(mode: SoundMode) {
        val client = session.avrClient ?: return
        viewModelScope.launch {
            runCatching { client.setSoundMode(mode) }
                .onSuccess { _uiState.value = _uiState.value.copy(soundMode = mode) }
        }
    }

    fun setBitPerfectPolicy(policy: BitPerfectPolicy) {
        viewModelScope.launch {
            settings.setBitPerfectPolicy(policy.name)
            _uiState.value = _uiState.value.copy(bitPerfectPolicy = policy)
        }
    }
}
