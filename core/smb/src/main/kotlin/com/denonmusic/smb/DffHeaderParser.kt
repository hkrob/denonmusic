package com.denonmusic.smb

import java.io.InputStream

/**
 * Reads a Philips DSDIFF (`.dff`) file's sample rate and channel count out of its `PROP`/`SND `
 * chunk. Everything in this format is big-endian IFF-style: 4-byte chunk id, 8-byte chunk size, then
 * that many bytes of data (padded to an even length).
 *
 * Top level: `FRM8` + size + `DSD ` form type, then sibling chunks including `PROP` (property chunk,
 * form type `SND `) which itself nests `FS  ` (4-byte big-endian sample rate) and `CHNL` (2-byte
 * channel count, followed by that many 4-byte channel id strings we don't need). DSD audio is
 * always 1 bit per sample, so that's a fixed constant here rather than something read off the wire.
 */
object DffHeaderParser {

    fun parse(input: InputStream): AudioFormatInfo? {
        if (!expectId(input, "FRM8")) return null
        skipBigEndianSize(input) // FRM8's own size covers the whole file; not needed to find PROP
        if (!expectId(input, "DSD ")) return null

        var sampleRate: Int? = null
        var channels: Int? = null

        while (sampleRate == null || channels == null) {
            val id = readId(input) ?: break
            val size = readBigEndianSize(input) ?: break
            if (id == "PROP") {
                if (!expectId(input, "SND ")) return null
                var remaining = size - 4
                while (remaining > 0 && (sampleRate == null || channels == null)) {
                    val subId = readId(input) ?: break
                    val subSize = readBigEndianSize(input) ?: break
                    remaining -= 12
                    when (subId) {
                        "FS  " -> {
                            val bytes = ByteArray(4)
                            if (readFully(input, bytes) != 4) break
                            sampleRate = beInt(bytes, 0)
                            skipPadded(input, subSize - 4)
                        }
                        "CHNL" -> {
                            val bytes = ByteArray(2)
                            if (readFully(input, bytes) != 2) break
                            channels = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                            skipPadded(input, subSize - 2)
                        }
                        else -> skipPadded(input, subSize)
                    }
                    remaining -= paddedSize(subSize)
                }
            } else {
                skipPadded(input, size)
            }
        }

        if (sampleRate == null || channels == null) return null
        return AudioFormatInfo(
            container = AudioContainer.Dff,
            sampleRateHz = sampleRate,
            bitsPerSample = 1,
            channels = channels,
        )
    }

    private fun expectId(input: InputStream, id: String) = readId(input) == id

    private fun readId(input: InputStream): String? {
        val bytes = ByteArray(4)
        if (readFully(input, bytes) != 4) return null
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readBigEndianSize(input: InputStream): Int? {
        val bytes = ByteArray(8)
        if (readFully(input, bytes) != 8) return null
        // Chunk sizes are 64-bit, but nothing this app reads is anywhere near 2GB; the low 32 bits
        // are all that's needed and avoids every call site juggling Long for a size that fits in Int.
        return beInt(bytes, 4)
    }

    private fun skipBigEndianSize(input: InputStream) {
        readFully(input, ByteArray(8))
    }

    private fun skipPadded(input: InputStream, size: Int) {
        if (size <= 0) return
        readFully(input, ByteArray(paddedSize(size)))
    }

    private fun paddedSize(size: Int) = size + (size % 2)
}

/** Big-endian 32-bit read at [offset] within [bytes]. */
internal fun beInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
