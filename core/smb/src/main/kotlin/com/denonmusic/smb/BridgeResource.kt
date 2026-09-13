package com.denonmusic.smb

import java.io.InputStream

/**
 * One servable file for [SmbBridgeServer]: its total length (needed for `Content-Length` and to
 * answer an open-ended `Range: bytes=N-`), a MIME type, and a way to open a stream starting at an
 * arbitrary byte offset.
 *
 * [openAt] reopens the underlying file and skips forward rather than seeking a single held-open
 * stream: `SmbFile`'s own input stream does support `skip`, and a fresh stream per request is far
 * simpler to reason about than juggling concurrent reads against one shared stream when nothing in
 * this app's usage needs more than one receiver fetching one file at a time.
 */
data class BridgeResource(
    val length: Long,
    val contentType: String,
    val openAt: (offset: Long) -> InputStream,
)

/** Maps a file extension to the MIME type the plan's format list expects a receiver to recognise. */
fun contentTypeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "dsf" -> "audio/x-dsd"
    "dff" -> "audio/x-dff"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "mp4" -> "audio/mp4"
    else -> "application/octet-stream"
}

/** True for an extension one of this project's own header parsers actually recognises. */
fun isSupportedAudioFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("flac", "mp3", "dsf", "dff", "m4a", "mp4")
