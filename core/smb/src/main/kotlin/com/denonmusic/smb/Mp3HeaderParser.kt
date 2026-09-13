package com.denonmusic.smb

import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Reads the first MPEG audio frame header (skipping a leading ID3v2 tag if present) and, when
 * present, the `Xing`/`Info`/`VBRI` tag that follows it - the only reliable way to tell a CBR MP3
 * from a VBR one, and to get an accurate average bitrate for the latter.
 *
 * Scoped to Layer III (what "MP3" means in practice); Layers I/II are rejected as unsupported rather
 * than mis-parsed.
 */
object Mp3HeaderParser {

    fun parse(rawInput: InputStream): AudioFormatInfo? {
        val input = PushbackInputStream(rawInput, 3)
        if (!skipId3v2(input)) return null
        val header = ByteArray(4)
        if (readFully(input, header) != 4) return null
        if (!isFrameSync(header)) return null

        val versionBits = (header[1].toInt() ushr 3) and 0x3
        val layerBits = (header[1].toInt() ushr 1) and 0x3
        if (layerBits != LAYER_III) return null
        val version = MpegVersion.fromBits(versionBits) ?: return null

        val bitrateIndex = (header[2].toInt() ushr 4) and 0xF
        val sampleRateIndex = (header[2].toInt() ushr 2) and 0x3
        val channelMode = (header[3].toInt() ushr 6) and 0x3

        val sampleRate = SAMPLE_RATES[version]?.get(sampleRateIndex) ?: return null
        val frameBitrateKbps = bitrateTable(version)[bitrateIndex]
        if (sampleRate <= 0 || frameBitrateKbps <= 0) return null
        val channels = if (channelMode == MONO_MODE) 1 else 2

        val sideInfoBytes = sideInfoBytes(version, channelMode)
        val vbrTag = readVbrTag(input, sideInfoBytes)

        val bitrateKbps = vbrTag?.averageBitrateKbps(version, sampleRate) ?: frameBitrateKbps
        val isVbr = vbrTag?.isVbr ?: false

        return AudioFormatInfo(
            container = AudioContainer.Mp3,
            sampleRateHz = sampleRate,
            bitsPerSample = 16, // MP3 decodes to 16-bit PCM; the format carries no bit-depth field
            channels = channels,
            bitrateKbps = bitrateKbps,
            isVbr = isVbr,
        )
    }

    private fun isFrameSync(header: ByteArray): Boolean =
        (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0

    /** True if the stream is now positioned right after any ID3v2 tag (or had none); false on error. */
    private fun skipId3v2(input: PushbackInputStream): Boolean {
        val marker = ByteArray(3)
        val markerRead = readFully(input, marker)
        if (markerRead != 3) return false
        if (String(marker, Charsets.US_ASCII) != "ID3") {
            input.unread(marker, 0, markerRead)
            return true
        }
        val rest = ByteArray(7)
        if (readFully(input, rest) != 7) return false
        // Tag size is syncsafe: 4 bytes, 7 significant bits each.
        val size = (rest[3].toInt() and 0x7F shl 21) or
            (rest[4].toInt() and 0x7F shl 14) or
            (rest[5].toInt() and 0x7F shl 7) or
            (rest[6].toInt() and 0x7F)
        return readFully(input, ByteArray(size)) == size
    }

    private data class VbrTag(val isVbr: Boolean, val frames: Int?, val bytes: Int?) {
        fun averageBitrateKbps(version: MpegVersion, sampleRateHz: Int): Int? {
            val f = frames ?: return null
            val b = bytes ?: return null
            val samplesPerFrame = if (version == MpegVersion.V1) 1152 else 576
            val durationSeconds = (f.toDouble() * samplesPerFrame) / sampleRateHz
            if (durationSeconds <= 0) return null
            return ((b * 8.0) / durationSeconds / 1000.0).toInt()
        }
    }

    private fun readVbrTag(input: InputStream, sideInfoBytes: Int): VbrTag? {
        val gap = ByteArray(sideInfoBytes)
        if (readFully(input, gap) != sideInfoBytes) return null
        val tagId = ByteArray(4)
        if (readFully(input, tagId) != 4) return null
        val tag = String(tagId, Charsets.US_ASCII)
        if (tag != "Xing" && tag != "Info") return null

        val flags = ByteArray(4)
        if (readFully(input, flags) != 4) return null
        val flagsInt = beInt(flags, 0)
        var frames: Int? = null
        var bytes: Int? = null
        if (flagsInt and 0x1 != 0) {
            val f = ByteArray(4)
            if (readFully(input, f) == 4) frames = beInt(f, 0)
        }
        if (flagsInt and 0x2 != 0) {
            val b = ByteArray(4)
            if (readFully(input, b) == 4) bytes = beInt(b, 0)
        }
        return VbrTag(isVbr = tag == "Xing", frames = frames, bytes = bytes)
    }

    /** Bytes between the 4-byte frame header and where a Xing/Info tag would begin. */
    private fun sideInfoBytes(version: MpegVersion, channelMode: Int): Int {
        val mono = channelMode == MONO_MODE
        return when (version) {
            MpegVersion.V1 -> if (mono) 17 else 32
            MpegVersion.V2, MpegVersion.V2_5 -> if (mono) 9 else 17
        }
    }

    private fun bitrateTable(version: MpegVersion): IntArray =
        if (version == MpegVersion.V1) BITRATES_V1_L3 else BITRATES_V2_L3

    private const val LAYER_III = 0x1
    private const val MONO_MODE = 0x3

    private enum class MpegVersion {
        V1,
        V2,
        V2_5,
        ;

        companion object {
            fun fromBits(bits: Int): MpegVersion? = when (bits) {
                0b11 -> V1
                0b10 -> V2
                0b00 -> V2_5
                else -> null // 0b01 is reserved
            }
        }
    }

    private val SAMPLE_RATES = mapOf(
        MpegVersion.V1 to intArrayOf(44100, 48000, 32000, -1),
        MpegVersion.V2 to intArrayOf(22050, 24000, 16000, -1),
        MpegVersion.V2_5 to intArrayOf(11025, 12000, 8000, -1),
    )

    private val BITRATES_V1_L3 =
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1)
    private val BITRATES_V2_L3 =
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1)
}
