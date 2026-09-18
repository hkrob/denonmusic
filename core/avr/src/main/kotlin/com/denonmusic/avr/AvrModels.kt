package com.denonmusic.avr

enum class PowerState(val wire: String) {
    On("PWON"),
    Standby("PWSTANDBY"),
    ;

    companion object {
        fun fromWire(line: String): PowerState? = entries.firstOrNull { line.startsWith(it.wire) }
    }
}

/**
 * The sound modes the plan calls out. `MSDIRECT`/`MSPUREDIRECT` (wire form has no space) are the two
 * that matter for bit-perfect DSD - every other mode converts DSD to PCM so the DSP chain can run.
 */
enum class SoundMode(val wire: String) {
    Movie("MSMOVIE"),
    Music("MSMUSIC"),
    Game("MSGAME"),
    Direct("MSDIRECT"),
    PureDirect("MSPURE DIRECT"),
    Stereo("MSSTEREO"),
    Auto("MSAUTO"),
    MultiChStereo("MSMCH STEREO"),
    DolbyDigital("MSDOLBY DIGITAL"),
    DtsSurround("MSDTS SURROUND"),
    Virtual("MSVIRTUAL"),
    Auro3d("MSAURO3D"),
    Auro2dSurround("MSAURO2DSURR"),
    NeuralX("MSNEURAL:X"),
    ;

    /** True in [Direct] or [PureDirect]: the only modes where DSD reaches the DAC unconverted. */
    val isBitPerfect: Boolean get() = this == Direct || this == PureDirect

    companion object {
        fun fromWire(line: String): SoundMode? = entries
            // Longest wire prefix first: "MSPURE DIRECT" must not be shadowed by a hypothetical
            // shorter "MS" match, and modes sharing a prefix (none currently do) would need this too.
            .sortedByDescending { it.wire.length }
            .firstOrNull { line.startsWith(it.wire) }
    }
}

/** How aggressively the app manages sound mode to keep DSD bit-perfect. See plan.md. */
enum class BitPerfectPolicy {
    Off,
    AutoDirect,
    AutoPureDirect,
    ;

    val targetMode: SoundMode? get() = when (this) {
        Off -> null
        AutoDirect -> SoundMode.Direct
        AutoPureDirect -> SoundMode.PureDirect
    }

    companion object {
        /**
         * The one place "what does a missing or garbled stored policy mean" is decided, so the three
         * call sites that used to answer it differently - default to [Off], silently apply nothing, or
         * echo the raw unvalidated string - can't drift again. [Off] is the correct default: it is the
         * only policy that changes nothing on the receiver, so a corrupt or pre-this-feature settings
         * value can never unexpectedly force a sound-mode switch.
         */
        fun fromStoredName(name: String?): BitPerfectPolicy =
            name?.let { stored -> entries.firstOrNull { it.name == stored } } ?: Off
    }
}

enum class SignalType {
    Pcm,
    Dsd,
    Analog,
    Unknown,
}

data class SignalInfo(val type: SignalType, val sampleRateKhz: Double?)

/** One line of `CV?`: the receiver reports currently-active output channels in half-dB units. */
data class OutputChannel(val code: String, val level: String)
