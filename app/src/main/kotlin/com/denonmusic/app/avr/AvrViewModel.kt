package com.denonmusic.app.avr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denonmusic.app.heos.HeosSession
import com.denonmusic.avr.AvrClient
import com.denonmusic.avr.BitPerfectPolicy
import com.denonmusic.avr.OutputChannel
import com.denonmusic.avr.PowerState
import com.denonmusic.avr.SignalType
import com.denonmusic.avr.SoundMode
import com.denonmusic.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val isRebooting: Boolean = false,
)

@HiltViewModel
class AvrViewModel @Inject constructor(
    private val session: AvrSession,
    private val heosSession: HeosSession,
    private val settings: SettingsRepository,
    private val bitPerfectPolicyController: BitPerfectPolicyController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvrUiState())
    val uiState: StateFlow<AvrUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settings.settings.first()
            _uiState.value = _uiState.value.copy(
                bitPerfectPolicy = bitPerfectPolicyController.storedPolicy(),
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

    /**
     * Persists the choice for [PlayerViewModel]'s own future queue-start edge, and also applies it to
     * the receiver right now - picking Auto Direct/Pure Direct while something is already playing is
     * a request to switch sound mode immediately, not just a preference for the next track. Without
     * this, choosing a policy here silently did nothing until playback happened to restart.
     */
    fun setBitPerfectPolicy(policy: BitPerfectPolicy) {
        val client = session.avrClient
        viewModelScope.launch {
            bitPerfectPolicyController.persistAndApply(client, policy)
            _uiState.value = _uiState.value.copy(bitPerfectPolicy = policy)
        }
    }

    /**
     * Reboots the HEOS network module via [HeosClient.reboot] - the documented fix for a telnet/HEOS
     * connection that's wedged until the receiver is power-cycled by hand (see that command's own
     * doc). Goes over the HEOS session, not [AvrSession]'s telnet connection: `system/reboot` is a
     * HEOS CLI command, and telnet has no equivalent. Confirmed live: both connections drop within
     * the request and the receiver is back and browsable roughly 90s later.
     */
    fun rebootReceiver() {
        val client = heosSession.heosClient ?: return
        _uiState.value = _uiState.value.copy(isRebooting = true)
        viewModelScope.launch {
            runCatching { client.reboot() }
            _uiState.value = _uiState.value.copy(isRebooting = false)
        }
    }
}
