package com.denonmusic.smb

import java.io.InputStream

/**
 * Reads a Sony DSF file's `fmt ` chunk. Everything in this format is little-endian, unlike DFF.
 *
 * Layout: a 28-byte `DSD ` chunk (id, size, total file size, pointer to an optional trailing id3
 * metadata chunk - none of which matters here), immediately followed by the `fmt ` chunk: 4-byte id,
 * 8-byte size (always 52, i.e. 12-byte chunk header + 40 bytes of fields below).
 *
 * ```
 * 4 bytes  format version
 * 4 bytes  format id (0 = DSD raw)
 * 4 bytes  channel type (1=mono .. 7=5.1)
 * 4 bytes  channel count
 * 4 bytes  sampling frequency in Hz (e.g. 2822400 for DSD64, 5644800 for DSD128)
 * 4 bytes  bits per sample (1 or 8 - packing width, not audio resolution; DSD is always 1-bit audio)
 * 8 bytes  sample count
 * 4 bytes  block size per channel
 * 4 bytes  reserved
 * ```
 */
object DsfHeaderParser {

    fun parse(input: InputStream): AudioFormatInfo? {
        val header = ByteArray(28)
        if (readFully(input, header) != 28 || String(header, 0, 4, Charsets.US_ASCII) != "DSD ") return null

        val fmtId = ByteArray(4)
        if (readFully(input, fmtId) != 4 || String(fmtId, Charsets.US_ASCII) != "fmt ") return null
        val fmtChunk = ByteArray(8 + 40)
        if (readFully(input, fmtChunk) != fmtChunk.size) return null

        val channelCount = leInt(fmtChunk, 8 + 12)
        val samplingFrequency = leInt(fmtChunk, 8 + 16)
        val bitsPerSample = leInt(fmtChunk, 8 + 20)

        return AudioFormatInfo(
            container = AudioContainer.Dsf,
            sampleRateHz = samplingFrequency,
            bitsPerSample = bitsPerSample,
            channels = channelCount,
        )
    }
}

/** Little-endian 32-bit read at [offset] within [bytes]. */
internal fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** [InputStream.read] can return short reads even mid-stream; loop to fill [buffer] or hit EOF. */
internal fun readFully(input: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = input.read(buffer, total, buffer.size - total)
        if (n == -1) break
        total += n
    }
    return total
}
