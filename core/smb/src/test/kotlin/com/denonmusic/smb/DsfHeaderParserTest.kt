package com.denonmusic.smb

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DsfHeaderParserTest {

    @Test
    fun `parses DSD64 stereo`() {
        val bytes = TestFixtures.dsf(sampleRate = 2_822_400, channels = 2, bitsPerSample = 1)

        val info = DsfHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(AudioFormatInfo(AudioContainer.Dsf, 2_822_400, 1, 2), info)
        assertEquals(true, info?.isDsd)
    }

    @Test
    fun `parses DSD128`() {
        val bytes = TestFixtures.dsf(sampleRate = 5_644_800, channels = 2, bitsPerSample = 1)

        val info = DsfHeaderParser.parse(ByteArrayInputStream(bytes))

        assertEquals(5_644_800, info?.sampleRateHz)
    }

    @Test
    fun `rejects a file without the DSD marker`() {
        val info = DsfHeaderParser.parse(ByteArrayInputStream(ByteArray(40)))

        assertNull(info)
    }
}
