package com.denonmusic.smb

enum class AudioContainer {
    Flac,
    Mp3,
    Dsf,
    Dff,
    Mp4,
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
    /**
     * The actual audio codec inside an [AudioContainer.Mp4] file, e.g. "AAC", "ALAC",
     * "E-AC-3 (Atmos)" - `container` alone only says the file is an MP4/M4A wrapper, not what's
     * encoded inside it. Null for every other container, where the container name already is the
     * codec name.
     */
    val codecLabel: String? = null,
    /** False for FLAC/DSF/DFF/ALAC; true for MP3 and every lossy MP4 codec (AAC, E-AC-3, AC-4). */
    val isLossy: Boolean = container == AudioContainer.Mp3,
) {
    val isDsd: Boolean get() = container == AudioContainer.Dsf || container == AudioContainer.Dff

    /** True for a codec whose object-based immersive-audio extension Atmos rips are muxed as. */
    val isAtmos: Boolean get() = codecLabel?.contains("Atmos", ignoreCase = true) == true

    /**
     * The Japan Audio Society's own Hi-Res Audio definition: lossless PCM at 24-bit/48kHz or above,
     * or DSD at any rate. A lossy codec (MP3, AAC, E-AC-3, AC-4) is never Hi-Res regardless of its
     * nominal sample rate - Atmos rips included, since the object-audio codecs above are lossy.
     */
    val isHiRes: Boolean get() = isDsd || (!isLossy && bitsPerSample >= 24 && sampleRateHz >= 48_000)
}
