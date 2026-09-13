package com.denonmusic.smb

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class AudioHeaderParserTest {

    @Test
    fun `dispatches by extension, case-insensitively`() {
        val bytes = TestFixtures.flacStreamInfo(44_100, 2, 16)

        val info = AudioHeaderParser.parse("Track.FLAC", ByteArrayInputStream(bytes))

        assertEquals(AudioContainer.Flac, info?.container)
    }

    @Test
    fun `unknown extensions are not parsed`() {
        val info = AudioHeaderParser.parse("track.wav", ByteArrayInputStream(ByteArray(10)))

        assertNull(info)
    }
}
