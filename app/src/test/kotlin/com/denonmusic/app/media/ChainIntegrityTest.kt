package com.denonmusic.app.media

import com.denonmusic.avr.SignalType
import com.denonmusic.smb.AudioContainer
import com.denonmusic.smb.AudioFormatInfo
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class ChainIntegrityTest {

    private val flac192 = AudioFormatInfo(AudioContainer.Flac, sampleRateHz = 192_000, bitsPerSample = 24, channels = 2)
    private val dsf = AudioFormatInfo(AudioContainer.Dsf, sampleRateHz = 2_822_400, bitsPerSample = 1, channels = 2)

    @Test
    fun `matches when PCM sample rate agrees with the receiver`() {
        val result = ChainIntegrity.evaluate(flac192, SignalType.Pcm, avrSampleRateKhz = 192.0)

        assertEquals(ChainIntegrityResult.Match, result)
    }

    @Test
    fun `flags a resample when the receiver reports a different PCM rate`() {
        val result = ChainIntegrity.evaluate(flac192, SignalType.Pcm, avrSampleRateKhz = 48.0)

        assertIs<ChainIntegrityResult.Mismatch>(result)
    }

    @Test
    fun `flags DSD arriving as PCM - the transcoding case the plan calls out`() {
        val result = ChainIntegrity.evaluate(dsf, SignalType.Pcm, avrSampleRateKhz = 176.4)

        assertIs<ChainIntegrityResult.Mismatch>(result)
    }

    @Test
    fun `matches when DSD arrives as DSD regardless of any PCM sample rate reading`() {
        val result = ChainIntegrity.evaluate(dsf, SignalType.Dsd, avrSampleRateKhz = null)

        assertEquals(ChainIntegrityResult.Match, result)
    }

    @Test
    fun `is unknown with no file-side info`() {
        val result = ChainIntegrity.evaluate(null, SignalType.Pcm, avrSampleRateKhz = 44.1)

        assertEquals(ChainIntegrityResult.Unknown, result)
    }

    @Test
    fun `is unknown with no AVR signal reading`() {
        val result = ChainIntegrity.evaluate(flac192, null, avrSampleRateKhz = null)

        assertEquals(ChainIntegrityResult.Unknown, result)
    }

    @Test
    fun `tolerates the receiver's sample rate field only carrying one decimal place`() {
        val result = ChainIntegrity.evaluate(flac192, SignalType.Pcm, avrSampleRateKhz = 192.0)

        assertEquals(ChainIntegrityResult.Match, result)
    }
}
