package com.denonmusic.smb

import java.io.InputStream

/**
 * Minimal ISO-BMFF (MP4/M4A) box walker: finds `moov > trak > mdia > minf > stbl > stsd`'s first
 * sample entry, which carries the actual audio codec, sample rate and channel count - `mp4a` (AAC),
 * `alac` (Apple Lossless), or one of the object-audio codecs Dolby Atmos rips are typically muxed as,
 * `ec-3` (Enhanced AC-3 with the JOC extension) or `ac-4`.
 *
 * Sequential, forward-skip only, matching this project's other header parsers and the bounded prefix
 * [SmbOverlay] hands them - not the whole file. Apple's own encoders (and every Atmos rip encountered
 * so far) write `moov` before `mdat`, so this reaches the sample description well inside that budget
 * in practice; a file muxed the other way around (`mdat` first, `moov` at the end) returns null once
 * the byte budget runs out, same as any other parser hitting content it can't make sense of.
 *
 * Takes the first `trak`'s `stsd` entry without checking its `hdlr` handler type, so a file with a
 * video track ordered before the audio track would misparse - out of scope for what this project
 * actually handles (audio-only Atmos/AAC/ALAC rips, no accompanying video track).
 */
object Mp4HeaderParser {

    fun parse(input: InputStream): AudioFormatInfo? = runCatching { walk(input, Long.MAX_VALUE) }.getOrNull()

    private data class BoxHeader(val type: String, val dataSize: Long, val headerLen: Long)

    private fun readBoxHeader(input: InputStream): BoxHeader? {
        val head = ByteArray(8)
        if (readFully(input, head) != 8) return null
        val size32 = beUInt32(head, 0)
        val type = String(head, 4, 4, Charsets.US_ASCII)
        if (size32 == 1L) {
            val ext = ByteArray(8)
            if (readFully(input, ext) != 8) return null
            val size64 = beUInt64(ext, 0)
            return BoxHeader(type, size64 - 16, 16)
        }
        if (size32 == 0L) return null // extends to EOF - nothing past this is worth chasing
        return BoxHeader(type, size32 - 8, 8)
    }

    /** Walks sibling boxes within a [budget]-byte region, descending into container boxes by name. */
    private fun walk(input: InputStream, budget: Long): AudioFormatInfo? {
        var consumed = 0L
        while (consumed + 8 <= budget) {
            val header = readBoxHeader(input) ?: return skipRemainderAndReturn(input, budget, consumed, null)
            consumed += header.headerLen
            if (consumed + header.dataSize > budget) return skipRemainderAndReturn(input, budget, consumed, null)
            val result = when (header.type) {
                in CONTAINER_BOXES -> walk(input, header.dataSize)
                "stsd" -> parseStsd(input, header.dataSize)
                else -> {
                    if (!skipFully(input, header.dataSize)) return skipRemainderAndReturn(input, budget, consumed, null)
                    null
                }
            }
            consumed += header.dataSize
            if (result != null) return result
        }
        return skipRemainderAndReturn(input, budget, consumed, null)
    }

    private fun skipRemainderAndReturn(
        input: InputStream,
        budget: Long,
        consumed: Long,
        result: AudioFormatInfo?,
    ): AudioFormatInfo? {
        val remainder = budget - consumed
        if (remainder > 0) skipFully(input, remainder)
        return result
    }

    /**
     * `stsd`: 1 byte version, 3 bytes flags, 4 bytes entry count, then one `SampleEntry` per format
     * (only the first is read - the plan has no use for a file offering more than one). Its own header
     * (size + fourcc) is followed by `SampleEntry`'s 6-byte reserved + 2-byte data_reference_index,
     * then `AudioSampleEntry`'s own reserved(8)/channelcount(2)/samplesize(2)/pre_defined(2)/
     * reserved(2)/samplerate(4, 16.16 fixed point) - 28 bytes total after the entry header.
     */
    private fun parseStsd(input: InputStream, size: Long): AudioFormatInfo? {
        var consumed = 0L
        fun readN(n: Int): ByteArray? {
            val buf = ByteArray(n)
            val got = readFully(input, buf)
            consumed += got
            return if (got == n) buf else null
        }
        fun finish(result: AudioFormatInfo?): AudioFormatInfo? = skipRemainderAndReturn(input, size, consumed, result)

        readN(8) ?: return finish(null) // version/flags/entry_count - unused
        val entryHeader = readN(8) ?: return finish(null)
        val entrySize = beUInt32(entryHeader, 0)
        val fourcc = String(entryHeader, 4, 4, Charsets.US_ASCII)
        val fields = readN(28) ?: return finish(null)
        var channels = be16(fields, 16)
        // The generic 16.16 fixed-point field above can only hold a 16-bit integer part - it cannot
        // represent a sample rate above 65535 Hz at all, which real muxers hit for every 96/192kHz
        // ALAC hi-res file. Overridden below from ALACSpecificConfig's own (real, unbounded) field
        // when present; left as-is for AAC/E-AC-3/AC-4, none of which are ever muxed above 48kHz.
        var sampleRateHz = (beUInt32(fields, 24) shr 16).toInt()
        var bitsPerSample = be16(fields, 18)

        // ALAC alone carries real lossless stream parameters, in a child `alac` box
        // (ALACSpecificConfig, 24 bytes) right after these 28 bytes: frameLength(4), compatible
        // Version(1), bitDepth(1), pb(1), mb(1), kb(1), numChannels(1), maxRun(2), maxFrameBytes(4),
        // avgBitRate(4), sampleRate(4) - the last field is this codec's actual sample rate, not the
        // capped one above.
        if (fourcc == "alac") {
            val remainingInEntry = entrySize - 8 - 28
            if (remainingInEntry >= 8) {
                val childHeader = readN(8)
                if (childHeader != null && String(childHeader, 4, 4, Charsets.US_ASCII) == "alac") {
                    val cfg = readN(24)
                    if (cfg != null) {
                        bitsPerSample = cfg[5].toInt() and 0xFF
                        channels = cfg[9].toInt() and 0xFF
                        sampleRateHz = beUInt32(cfg, 20).toInt()
                    }
                }
            }
        }

        val codecLabel = CODEC_LABELS[fourcc] ?: fourcc.trim().uppercase()
        return finish(
            AudioFormatInfo(
                container = AudioContainer.Mp4,
                sampleRateHz = sampleRateHz,
                bitsPerSample = bitsPerSample,
                channels = channels,
                codecLabel = codecLabel,
                isLossy = fourcc !in LOSSLESS_FOURCCS,
            ),
        )
    }

    private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl")

    private val CODEC_LABELS = mapOf(
        "mp4a" to "AAC",
        "alac" to "ALAC",
        "ec-3" to "E-AC-3 (Atmos)",
        "ac-4" to "AC-4 (Atmos)",
        "Opus" to "Opus",
    )

    private val LOSSLESS_FOURCCS = setOf("alac")

    private fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun beUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun beUInt64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return result
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }
}
