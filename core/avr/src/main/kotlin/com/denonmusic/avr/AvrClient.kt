package com.denonmusic.avr

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Typed wrapper over [AvrConnection] for the commands the plan calls out: power/input for
 * auto-power-on, sound mode for the bit-perfect policy, `SSINF*` for the technical panel, and `CV?`
 * for the speaker OUTPUT map.
 */
class AvrClient(private val connection: AvrConnection) {

    // -- power / input -------------------------------------------------------

    suspend fun powerState(): PowerState? = PowerState.fromWire(connection.query("PW?", "PW"))

    suspend fun powerOn() = connection.send(PowerState.On.wire)

    suspend fun powerStandby() = connection.send(PowerState.Standby.wire)

    suspend fun mainZoneOn() = connection.send("ZMON")

    /** e.g. `SICD`, `SINET` - strips the two-letter `SI` prefix. */
    suspend fun inputSource(): String = connection.query("SI?", "SI").removePrefix("SI")

    suspend fun selectInput(mnemonic: String) = connection.send("SI$mnemonic")

    /**
     * Brings the receiver up and onto the network input if it's asleep or on something else -
     * the auto-power-on path the plan calls for before the first queue action.
     *
     * Waits for the receiver's own `PWON` echo rather than assuming the command took, since the
     * event stream (not optimistic local state) is what the rest of the app trusts.
     */
    suspend fun ensureOnAndSelected(inputMnemonic: String, powerOnTimeoutMillis: Long = 8_000) {
        if (powerState() == PowerState.Standby) {
            powerOn()
            withTimeoutOrNull(powerOnTimeoutMillis) {
                connection.events.first { it.startsWith(PowerState.On.wire) }
            }
        }
        mainZoneOn()
        selectInput(inputMnemonic)
    }

    // -- sound mode ------------------------------------------------------------

    suspend fun soundMode(): SoundMode? = SoundMode.fromWire(connection.query("MS?", "MS"))

    suspend fun setSoundMode(mode: SoundMode) = connection.send(mode.wire)

    /**
     * No-op when [policy] is [BitPerfectPolicy.Off].
     *
     * Engaging `MSPURE DIRECT` on the AVR-X4500H probed for this project only auto-dims its own front
     * display to `DIM DAR` (Dark), not fully off, even though Denon's own docs describe Pure Direct as
     * disabling the display outright. Tried overriding that with an explicit `DIM` setter command
     * (`DIM OFF`, `DIM DAR`, and `DIMDAR` with no separator) directly over telnet - none of the three
     * changed what a subsequent `DIM ?` reported back, so this receiver's front-display dimmer isn't
     * remotely controllable through this command at all on this model/firmware, or needs a command
     * this project has no documentation for. Not chased further - the sound mode itself (the part that
     * actually matters for bit-perfect playback) does switch correctly; the display staying lit is a
     * receiver behavior outside this app's control, not a bug in [setSoundMode] below.
     */
    suspend fun applyBitPerfectPolicy(policy: BitPerfectPolicy) {
        policy.targetMode?.let { setSoundMode(it) }
    }

    // -- volume / mute (advanced, absolute-dB path; HEOS volume is preferred for session use) ------

    /**
     * Denon's `MV` encodes half-dB steps as digits with an implicit `-80dB` floor: two digits are
     * whole dB (`MV50` -> 50 -> -30dB), three digits carry a half-dB remainder divided by 10
     * (`MV505` -> 50.5 -> -29.5dB).
     */
    suspend fun volumeDb(): Double? {
        val digits = connection.query("MV?", "MV").removePrefix("MV").takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val value = if (digits.length == 3) digits.toInt() / 10.0 else digits.toInt().toDouble()
        return value - VOLUME_FLOOR_DB
    }

    suspend fun setVolumeDb(db: Double) {
        val value = (db + VOLUME_FLOOR_DB).coerceIn(0.0, 98.0)
        val whole = value.toInt()
        val wireValue = if (value - whole >= 0.25) "${whole}5" else whole.toString().padStart(2, '0')
        connection.send("MV$wireValue")
    }

    suspend fun isMuted(): Boolean = connection.query("MU?", "MU") == "MUON"

    suspend fun setMute(mute: Boolean) = connection.send(if (mute) "MUON" else "MUOFF")

    // -- technical panel ---------------------------------------------------

    /**
     * The signal the receiver is currently *receiving*, independent of what's playing according to
     * HEOS - comparing the two is the chain-integrity check the plan describes.
     *
     * `SSINFAISSIG ?` answers with a numeric code line and, per the probe findings, a human-readable
     * `SYSDA` label line (`SYSDA PCM` / `SYSDA DSD` / ...); either is enough to classify, and the
     * label needs no lookup table, so it wins when both arrive.
     */
    suspend fun signalType(): SignalType {
        val lines = runCatching {
            connection.queryMatchingAny("SSINFAISSIG ?", setOf("SSINFAISSIG", "SYSDA"))
        }.getOrDefault(emptyList())
        val label = lines.firstOrNull { it.startsWith("SYSDA") }
        val code = lines.firstOrNull { it.startsWith("SSINFAISSIG") }
        return (label ?: code)?.let(::classifyByCode) ?: SignalType.Unknown
    }

    private fun classifyByCode(line: String): SignalType = when {
        line.contains("PCM", ignoreCase = true) -> SignalType.Pcm
        line.contains("DSD", ignoreCase = true) -> SignalType.Dsd
        line.contains("ANALOG", ignoreCase = true) -> SignalType.Analog
        // The AVR-X4500H probed for this project never sends a `SYSDA` label for network-sourced
        // audio (only for HDMI/optical inputs, per the probe) - only the bare numeric code, which
        // this project has no official table for. Empirically, every HEOS/DLNA-streamed track this
        // app has queued - regardless of the file's own container or codec - reports code 18, which
        // matches the architecture: HEOS's network module always decodes to PCM internally before
        // handing off to the amp section, so a network stream can never arrive as anything else here.
        line.removePrefix("SSINFAISSIG").trim() == "18" -> SignalType.Pcm
        else -> SignalType.Unknown
    }

    /** `SSINFAISFSV ?` -> e.g. `SSINFAISFSV 441` meaning 44.1 kHz (value is kHz * 10). */
    suspend fun sampleRateKhz(): Double? {
        val line = connection.query("SSINFAISFSV ?", "SSINFAISFSV")
        val digits = line.removePrefix("SSINFAISFSV").trim().takeWhile { it.isDigit() }
        return digits.toIntOrNull()?.let { it / 10.0 }
    }

    /**
     * `CV?` returns one `CV<channel> <level>` line per currently-active output channel, terminated by
     * a literal `CVEND` line - the green tiles on the Denon app's OUTPUT screen. Everything configured
     * but absent from this list is the grey tiles; there is no separate "off" signal for them.
     */
    suspend fun outputChannels(): List<OutputChannel> =
        connection.queryUntilTerminator("CV?", "CV", terminator = "CVEND")
            .filterNot { it.startsWith("CVEND") }
            .map(::parseChannelLine)

    companion object {
        private const val VOLUME_FLOOR_DB = 80.0

        /**
         * Parses one `CV<channel> <level>` line. Shared with callers that read the output map
         * straight off the live event stream instead of querying: the same real AVR-X4500H that
         * answers `CV?` also free-runs this exact line shape unprompted roughly once a second, so a
         * live view can stay current for free without ever re-querying (and racing its own query).
         */
        fun parseChannelLine(line: String): OutputChannel {
            val body = line.removePrefix("CV")
            val code = body.takeWhile { !it.isWhitespace() }
            val level = body.removePrefix(code).trim()
            return OutputChannel(code = code, level = level)
        }
    }
}
