package com.denonmusic.smb

import java.io.InputStream

/** Picks a format parser by file extension and hands it the stream. */
object AudioHeaderParser {

    fun parse(fileName: String, input: InputStream): AudioFormatInfo? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> FlacHeaderParser.parse(input)
            "mp3" -> Mp3HeaderParser.parse(input)
            "dsf" -> DsfHeaderParser.parse(input)
            "dff" -> DffHeaderParser.parse(input)
            "m4a", "mp4" -> Mp4HeaderParser.parse(input)
            else -> null
        }
}
