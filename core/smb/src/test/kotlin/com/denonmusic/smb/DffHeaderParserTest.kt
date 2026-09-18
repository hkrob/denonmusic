package com.denonmusic.smb

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DffHeaderParserTest {

    @Test
    fun `parses sample rate and channel count from the PROP SND chunk`() {
        val bytes = TestFixtures.dff(sampleRate = 2_822_400, channels = 2)

        val info = DffHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(AudioFormatInfo(AudioContainer.Dff, 2_822_400, 1, 2), info)
    }

    @Test
    fun `DSD is always reported as 1 bit per sample`() {
        val bytes = TestFixtures.dff(sampleRate = 2_822_400, channels = 2)

        val info = DffHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(1, info?.bitsPerSample)
    }

    @Test
    fun `mono file reports one channel`() {
        val bytes = TestFixtures.dff(sampleRate = 2_822_400, channels = 1)

        val info = DffHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(1, info?.channels)
    }

    @Test
    fun `rejects a file without the FRM8 marker`() {
        val info = DffHeaderParser.parse(ByteArrayInputStream(ByteArray(40)))

        assertNull(info)
    }
}
