package com.denonmusic.app.media

import com.denonmusic.avr.SignalType
import com.denonmusic.smb.AudioFormatInfo
import kotlin.math.abs

/**
 * Compares the file-side truth (parsed straight from the header via [com.denonmusic.smb]) against
 * the AVR-side truth (`SSINF*`, gathered independently over telnet) - the payoff for browsing with an
 * SMB overlay rather than trusting HEOS metadata alone. A DSF file arriving as PCM means the DLNA
 * server transcoded it; a 24/192 FLAC arriving at 48 kHz means something resampled it.
 */
sealed interface ChainIntegrityResult {
    /** Either side of the comparison isn't available yet (no SMB path resolved, or no AVR reading). */
    data object Unknown : ChainIntegrityResult
    data object Match : ChainIntegrityResult
    data class Mismatch(val description: String) : ChainIntegrityResult
}

object ChainIntegrity {

    fun evaluate(
        fileInfo: AudioFormatInfo?,
        avrSignalType: SignalType?,
        avrSampleRateKhz: Double?,
    ): ChainIntegrityResult {
        if (fileInfo == null || avrSignalType == null) return ChainIntegrityResult.Unknown

        val expectedSignal = if (fileInfo.isDsd) SignalType.Dsd else SignalType.Pcm
        if (avrSignalType != expectedSignal) {
            val fileKind = if (fileInfo.isDsd) "DSD" else "PCM"
            return ChainIntegrityResult.Mismatch(
                "File is $fileKind but the receiver is getting ${avrSignalType.name.uppercase()} - " +
                    "something in the chain converted it.",
            )
        }

        if (!fileInfo.isDsd && avrSampleRateKhz != null) {
            val fileSampleKhz = fileInfo.sampleRateHz / 1000.0
            if (abs(fileSampleKhz - avrSampleRateKhz) > SAMPLE_RATE_TOLERANCE_KHZ) {
                return ChainIntegrityResult.Mismatch(
                    "File is $fileSampleKhz kHz but the receiver is getting $avrSampleRateKhz kHz - " +
                        "something resampled it.",
                )
            }
        }

        return ChainIntegrityResult.Match
    }

    private const val SAMPLE_RATE_TOLERANCE_KHZ = 0.1
}
