package com.denonmusic.app.ui

/**
 * Best-effort file-type badge from a bare name/title, for the places that only ever see a display
 * name rather than a real SMB path - a native HEOS/DLNA browse row, or the Now Playing title for a
 * track queued off the HEOS-native SMB share (whose `song` field is the raw filename, extension
 * included, unlike a tagged DLNA server). Anything without a recognised audio extension - which is
 * most DLNA/Plex-tagged titles - simply yields nothing rather than a wrong guess.
 */
private val KNOWN_AUDIO_EXTENSIONS = setOf(
    "flac", "mp3", "wav", "dsf", "dff", "m4a", "mp4", "alac", "aac", "wma", "ogg", "aiff",
)

fun fileTypeLabel(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it.isNotEmpty() && it in KNOWN_AUDIO_EXTENSIONS }?.uppercase()
}
