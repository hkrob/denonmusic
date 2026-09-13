package com.denonmusic.smb

enum class AudioContainer {
    Flac,
    Mp3,
    Dsf,
    Dff,
}

/**
 * File-side truth about an audio file, read directly from its header - independent of whatever the
 * AVR or HEOS report about what's actually reaching the DAC. Comparing the two is the plan's
 * chain-integrity check.
 */
data class AudioFormatInfo(
    val container: AudioContainer,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    /** Null when not computable from the header alone (e.g. FLAC's own frames aren't sized here). */
    val bitrateKbps: Int? = null,
    /** MP3 only: true for a `Xing`/`VBRI` tag, false for a bare CBR frame or a LAME `Info` tag. */
    val isVbr: Boolean? = null,
) {
    val isDsd: Boolean get() = container == AudioContainer.Dsf || container == AudioContainer.Dff
}
