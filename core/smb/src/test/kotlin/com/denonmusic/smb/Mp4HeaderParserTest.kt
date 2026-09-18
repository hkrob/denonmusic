package com.denonmusic.smb

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp4HeaderParserTest {

    @Test
    fun `parses an AAC m4a as lossy, not hi-res`() {
        val bytes = TestFixtures.mp4(fourcc = "mp4a", sampleRate = 44_100, channels = 2)

        val info = Mp4HeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(AudioContainer.Mp4, info?.container)
        assertEquals(44_100, info?.sampleRateHz)
        assertEquals(2, info?.channels)
        assertEquals("AAC", info?.codecLabel)
        assertTrue(info?.isLossy == true)
        assertFalse(info?.isHiRes == true)
        assertFalse(info?.isAtmos == true)
    }

    @Test
    fun `parses a 24-bit ALAC file as lossless hi-res`() {
        val bytes = TestFixtures.mp4(fourcc = "alac", sampleRate = 96_000, channels = 2, alacBitDepth = 24)

        val info = Mp4HeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals("ALAC", info?.codecLabel)
        assertEquals(24, info?.bitsPerSample)
        assertFalse(info?.isLossy == true)
        assertTrue(info?.isHiRes == true)
    }

    @Test
    fun `labels E-AC-3 as Atmos-capable and lossy`() {
        val bytes = TestFixtures.mp4(fourcc = "ec-3", sampleRate = 48_000, channels = 2)

        val info = Mp4HeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals("E-AC-3 (Atmos)", info?.codecLabel)
        assertTrue(info?.isAtmos == true)
        assertTrue(info?.isLossy == true)
        assertFalse(info?.isHiRes == true, "a lossy codec is never Hi-Res regardless of sample rate")
    }

    @Test
    fun `rejects a file with no moov box within the read budget`() {
        val info = Mp4HeaderParser.parse(ByteArrayInputStream(ByteArray(40)))

        assertNull(info)
    }
}
