package com.denonmusic.smb

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class Mp3HeaderParserTest {

    @Test
    fun `parses a CBR frame's bitrate directly from the header`() {
        val bytes = TestFixtures.mp3Cbr(bitrateIndex = 9) // index 9 = 128kbps for MPEG1 Layer III

        val info = Mp3HeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(44_100, info?.sampleRateHz)
        assertEquals(2, info?.channels)
        assertEquals(128, info?.bitrateKbps)
        assertFalse(info!!.isVbr!!)
    }

    @Test
    fun `mono channel mode reports one channel`() {
        val bytes = TestFixtures.mp3Cbr(bitrateIndex = 9, channelMode = 0b11)

        val info = Mp3HeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(1, info?.channels)
    }

    @Test
    fun `a Xing tag marks the file VBR and its frame-bytes pair gives the true average bitrate`() {
        val frames = 100
        val bytes = 62_693
        val fixture = TestFixtures.mp3Vbr(frames = frames, bytes = bytes)

        val info = Mp3HeaderParser.parse(ByteArrayInputStream(fixture))

        assertTrue(info!!.isVbr!!)
        val expectedKbps = ((bytes * 8.0) / (frames * 1152.0 / 44_100) / 1000.0).toInt()
        assertEquals(expectedKbps, info.bitrateKbps)
    }

    @Test
    fun `skips a leading ID3v2 tag before looking for the frame sync`() {
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 10) +
            ByteArray(10) // 10-byte tag body, matching the syncsafe size field above
        val fixture = id3 + TestFixtures.mp3Cbr(bitrateIndex = 9)

        val info = Mp3HeaderParser.parse(ByteArrayInputStream(fixture))

        assertEquals(128, info?.bitrateKbps)
    }

    @Test
    fun `rejects a stream with no frame sync`() {
        val info = Mp3HeaderParser.parse(ByteArrayInputStream(ByteArray(20)))

        assertNull(info)
    }
}
