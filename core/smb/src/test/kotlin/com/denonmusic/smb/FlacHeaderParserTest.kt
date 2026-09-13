package com.denonmusic.smb

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class FlacHeaderParserTest {

    @Test
    fun `parses a 16-bit 44_1kHz stereo file`() {
        val bytes = TestFixtures.flacStreamInfo(sampleRate = 44_100, channels = 2, bitsPerSample = 16)

        val info = FlacHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(AudioFormatInfo(AudioContainer.Flac, 44_100, 16, 2), info)
    }

    @Test
    fun `parses a 24-bit 192kHz stereo file`() {
        val bytes = TestFixtures.flacStreamInfo(sampleRate = 192_000, channels = 2, bitsPerSample = 24)

        val info = FlacHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(AudioFormatInfo(AudioContainer.Flac, 192_000, 24, 2), info)
    }

    @Test
    fun `parses mono`() {
        val bytes = TestFixtures.flacStreamInfo(sampleRate = 44_100, channels = 1, bitsPerSample = 16)

        val info = FlacHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(1, info?.channels)
    }

    @Test
    fun `rejects a file without the fLaC magic`() {
        val info = FlacHeaderParser.parse(ByteArrayInputStream("garbage".toByteArray()))

        assertNull(info)
    }
}
