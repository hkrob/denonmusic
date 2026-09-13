package com.denonmusic.smb

import java.io.DataInputStream
import java.io.InputStream

/**
 * Reads just the `STREAMINFO` metadata block - the first block in every valid FLAC file, and the
 * only one needed for format detail. Fixed 34-byte layout per the FLAC spec:
 *
 * ```
 * 16 bits  minimum block size
 * 16 bits  maximum block size
 * 24 bits  minimum frame size
 * 24 bits  maximum frame size
 * 20 bits  sample rate
 *  3 bits  channels - 1
 *  5 bits  bits per sample - 1
 * 36 bits  total samples
 * 128 bits MD5 signature
 * ```
 *
 * The last four fields before the MD5 pack into exactly 64 bits (20+3+5+36), which is why this reads
 * them as one big-endian `Long` rather than bit-by-bit.
 */
object FlacHeaderParser {

    fun parse(input: InputStream): AudioFormatInfo? {
        val stream = DataInputStream(input)
        val magic = ByteArray(4)
        if (stream.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null

        val blockHeader = stream.readInt() // 1 byte last-flag+type, 3 bytes length, read together as Int
        val type = (blockHeader ushr 24) and 0x7F
        if (type != STREAMINFO_TYPE) return null // spec guarantees STREAMINFO is first; anything else is malformed

        stream.skipNBytesCompat(10) // min/max block size, min/max frame size - not needed here
        val packed = stream.readLong()
        val sampleRate = (packed ushr 44 and 0xFFFFF).toInt()
        val channels = (packed ushr 41 and 0x7).toInt() + 1
        val bitsPerSample = (packed ushr 36 and 0x1F).toInt() + 1

        return AudioFormatInfo(
            container = AudioContainer.Flac,
            sampleRateHz = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
        )
    }

    private const val STREAMINFO_TYPE = 0
}

/** [InputStream.skipNBytes] needs API 34; this project's minSdk is 26. */
internal fun DataInputStream.skipNBytesCompat(count: Int) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining.toLong())
        if (skipped <= 0) {
            if (read() == -1) throw java.io.EOFException()
            remaining--
        } else {
            remaining -= skipped.toInt()
        }
    }
}
