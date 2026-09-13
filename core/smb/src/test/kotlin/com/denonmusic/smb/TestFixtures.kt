package com.denonmusic.smb

import java.io.ByteArrayOutputStream

/** Hand-built minimal-but-valid byte sequences for each format, used instead of binary fixture
 * files on disk - the parsers only ever look at a handful of header bytes, so a real encoder's
 * output would add nothing but binary noise to the repo for the same coverage. */
object TestFixtures {

    fun flacStreamInfo(sampleRate: Int, channels: Int, bitsPerSample: Int, totalSamples: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22)) // last=1, type=0 (STREAMINFO), length=34
        out.write(ByteArray(10)) // min/max block size, min/max frame size - unused by the parser
        val packed = (sampleRate.toLong() and 0xFFFFF shl 44) or
            ((channels - 1).toLong() and 0x7 shl 41) or
            ((bitsPerSample - 1).toLong() and 0x1F shl 36) or
            (totalSamples and 0xFFFFFFFFFL)
        out.write(beBytes(packed))
        out.write(ByteArray(16)) // MD5
        return out.toByteArray()
    }

    /** [bitrateIndex]/[sampleRateIndex]/[channelMode] are the raw 4/2/2-bit MPEG1 Layer III fields. */
    fun mp3FrameHeader(bitrateIndex: Int, sampleRateIndex: Int = 0, channelMode: Int = 0): ByteArray {
        val byte1 = (0xE0 or (0b11 shl 3) or (0b01 shl 1) or 0b1)
        val byte2 = (bitrateIndex shl 4) or (sampleRateIndex shl 2)
        val byte3 = channelMode shl 6
        return byteArrayOf(0xFF.toByte(), byte1.toByte(), byte2.toByte(), byte3.toByte())
    }

    fun mp3Cbr(bitrateIndex: Int = 9, sampleRateIndex: Int = 0, channelMode: Int = 0): ByteArray {
        val sideInfoBytes = if (channelMode == 0b11) 17 else 32
        return mp3FrameHeader(bitrateIndex, sampleRateIndex, channelMode) + ByteArray(sideInfoBytes)
    }

    fun mp3Vbr(frames: Int, bytes: Int, sampleRateIndex: Int = 0, channelMode: Int = 0): ByteArray {
        val sideInfoBytes = if (channelMode == 0b11) 17 else 32
        val out = ByteArrayOutputStream()
        out.write(mp3FrameHeader(bitrateIndex = 9, sampleRateIndex = sampleRateIndex, channelMode = channelMode))
        out.write(ByteArray(sideInfoBytes))
        out.write("Xing".toByteArray(Charsets.US_ASCII))
        out.write(beIntBytes(0x3)) // flags: frames + bytes present
        out.write(beIntBytes(frames))
        out.write(beIntBytes(bytes))
        return out.toByteArray()
    }

    fun dsf(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write(leLongBytes(28)) // DSD chunk size
        out.write(leLongBytes(0)) // total file size - unused
        out.write(leLongBytes(0)) // metadata pointer - unused
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leLongBytes(52))
        out.write(leIntBytes(1)) // format version
        out.write(leIntBytes(0)) // format id: DSD raw
        out.write(leIntBytes(if (channels == 1) 1 else 2)) // channel type
        out.write(leIntBytes(channels))
        out.write(leIntBytes(sampleRate))
        out.write(leIntBytes(bitsPerSample))
        out.write(leLongBytes(0)) // sample count - unused
        out.write(leIntBytes(4096)) // block size per channel
        out.write(leIntBytes(0)) // reserved
        return out.toByteArray()
    }

    fun dff(sampleRate: Int, channels: Int): ByteArray {
        val channelIds = (1..channels).joinToString("") { "SLFT" }.toByteArray(Charsets.US_ASCII)
        val chnlData = beShortBytes(channels) + channelIds
        val fsData = beIntBytes(sampleRate)

        val propInner = ByteArrayOutputStream()
        propInner.write("SND ".toByteArray(Charsets.US_ASCII))
        propInner.write("FS  ".toByteArray(Charsets.US_ASCII))
        propInner.write(beLongBytes(fsData.size.toLong()))
        propInner.write(fsData)
        propInner.write("CHNL".toByteArray(Charsets.US_ASCII))
        propInner.write(beLongBytes(chnlData.size.toLong()))
        propInner.write(chnlData)
        if (chnlData.size % 2 != 0) propInner.write(0)
        val propBytes = propInner.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("FRM8".toByteArray(Charsets.US_ASCII))
        out.write(beLongBytes(0)) // FRM8's own size - not read by the parser
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write("PROP".toByteArray(Charsets.US_ASCII))
        out.write(beLongBytes(propBytes.size.toLong()))
        out.write(propBytes)
        return out.toByteArray()
    }

    fun flacWithPicture(pictureBytes: ByteArray, mime: String = "image/jpeg"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x22)) // STREAMINFO, not last
        out.write(ByteArray(34))

        val picture = ByteArrayOutputStream()
        picture.write(beIntBytes(3)) // picture type: front cover
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        picture.write(beIntBytes(mimeBytes.size))
        picture.write(mimeBytes)
        picture.write(beIntBytes(0)) // no description
        picture.write(ByteArray(16)) // width, height, depth, colors used
        picture.write(beIntBytes(pictureBytes.size))
        picture.write(pictureBytes)
        val pictureBlock = picture.toByteArray()

        out.write(byteArrayOf(0x86.toByte()) + beBytes(pictureBlock.size.toLong()).takeLast(3).toByteArray())
        out.write(pictureBlock)
        return out.toByteArray()
    }

    fun id3WithApic(pictureBytes: ByteArray, mime: String = "image/jpeg"): ByteArray {
        val frame = ByteArrayOutputStream()
        frame.write(0) // text encoding: ISO-8859-1
        frame.write(mime.toByteArray(Charsets.US_ASCII))
        frame.write(0) // MIME terminator
        frame.write(3) // picture type: front cover
        frame.write(0) // empty description, terminated
        frame.write(pictureBytes)
        val frameBytes = frame.toByteArray()

        val frameHeader = ByteArrayOutputStream()
        frameHeader.write("APIC".toByteArray(Charsets.US_ASCII))
        frameHeader.write(beIntBytes(frameBytes.size)) // v2.3 regular size
        frameHeader.write(byteArrayOf(0, 0)) // flags

        val tagBody = frameHeader.toByteArray() + frameBytes

        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 0)) // version 2.3
        out.write(0) // flags
        out.write(syncsafeBytes(tagBody.size))
        out.write(tagBody)
        return out.toByteArray()
    }

    /**
     * A minimal but structurally real ISO-BMFF box tree: `ftyp`, then `moov > trak > mdia > minf >
     * stbl > stsd` holding one sample entry for [fourcc] ("mp4a", "alac", "ec-3", ...). [alacBitDepth]
     * appends an ALACSpecificConfig child box when [fourcc] is "alac", matching what a real encoder
     * writes there.
     */
    fun mp4(fourcc: String, sampleRate: Int, channels: Int, alacBitDepth: Int? = null): ByteArray {
        fun box(type: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(beIntBytes(8 + body.size))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(body)
            return out.toByteArray()
        }

        // The 16.16 fixed-point field can only hold a 16-bit integer part - a real muxer clamps to
        // 65535 rather than overflow it for a >65535Hz stream, same as reproduced here.
        val sampleRateFixed = (sampleRate.coerceAtMost(65_535).toLong() shl 16)
        val audioFields = ByteArray(6) // reserved
            .plus(byteArrayOf(0, 1)) // data_reference_index = 1
            .plus(ByteArray(8)) // reserved
            .plus(beShortBytes(channels))
            .plus(beShortBytes(16)) // samplesize
            .plus(byteArrayOf(0, 0)) // pre_defined
            .plus(byteArrayOf(0, 0)) // reserved
            .plus(beIntBytes(sampleRateFixed.toInt()))

        val childConfig = if (fourcc == "alac" && alacBitDepth != null) {
            // ALACSpecificConfig (24 bytes): frameLength(4), compatibleVersion(1), bitDepth(1),
            // pb(1), mb(1), kb(1), numChannels(1), maxRun(2), maxFrameBytes(4), avgBitRate(4),
            // sampleRate(4) - the real, unbounded sample rate this format actually needs.
            val config = beIntBytes(4096) +
                byteArrayOf(0, alacBitDepth.toByte(), 40, 10, 14, channels.toByte()) +
                beShortBytes(255) +
                beIntBytes(0) +
                beIntBytes(0) +
                beIntBytes(sampleRate)
            box("alac", config)
        } else {
            ByteArray(0)
        }
        val sampleEntry = box(fourcc, audioFields + childConfig)

        val stsdBody = beIntBytes(0) /* version/flags */ + beIntBytes(1) /* entry_count */ + sampleEntry
        val stsd = box("stsd", stsdBody)
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val ftyp = box("ftyp", "M4A mp42isom".toByteArray(Charsets.US_ASCII))
        return ftyp + moov
    }

    private fun beBytes(value: Long): ByteArray = ByteArray(8) { i -> (value ushr ((7 - i) * 8)).toByte() }
    private fun beIntBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )
    private fun beShortBytes(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun beLongBytes(value: Long): ByteArray = beBytes(value)
    private fun leIntBytes(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )
    private fun leLongBytes(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (i * 8)).toByte() }
    private fun syncsafeBytes(size: Int): ByteArray = byteArrayOf(
        (size ushr 21 and 0x7F).toByte(),
        (size ushr 14 and 0x7F).toByte(),
        (size ushr 7 and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )
}
