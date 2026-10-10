package com.denonmusic.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One Plex playlist, as returned by `GET /playlists`. */
data class PlexPlaylist(val ratingKey: String, val title: String, val itemCount: Int)

/**
 * One track inside a playlist, as returned by `GET /playlists/{ratingKey}/items`, in playlist
 * order - Plex's own order, which [PlexClient] does not re-sort.
 */
data class PlexPlaylistItem(
    val ratingKey: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

// --- Wire shapes -------------------------------------------------------------------------------
// Plex's JSON (`Accept: application/json`) wraps every response in one `MediaContainer`, whose
// `Metadata` array holds whatever was asked for - playlists themselves, or a playlist's items -
// shaped identically enough to share one DTO. `ignoreUnknownKeys` on the decoder (see PlexClient)
// is deliberate: this was written from Plex's community-documented API shape, not verified
// field-by-field against a real authenticated response (building this needed a token this project
// doesn't have yet - see docs/local-setup.md). Only the fields this app actually reads are modelled.

@Serializable
internal data class PlexResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexMediaContainer = PlexMediaContainer(),
)

@Serializable
internal data class PlexMediaContainer(
    @SerialName("Metadata") val metadata: List<PlexMetadataDto> = emptyList(),
)

@Serializable
internal data class PlexMetadataDto(
    val ratingKey: String? = null,
    val title: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val duration: Long? = null,
    /** Item count, present on a playlist's own row, absent on a track row. */
    val leafCount: Int? = null,
)
