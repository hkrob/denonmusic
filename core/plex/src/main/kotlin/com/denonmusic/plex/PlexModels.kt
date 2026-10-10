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

/**
 * One track found by [PlexClient.findLibraryTracks] - the reverse direction from
 * [PlexPlaylistItem]: starting from a HEOS-side track (name/artist/album) and looking for its Plex
 * `ratingKey`, to add it to a playlist. See the app's `PlaylistRepository` for how this is matched.
 */
data class PlexLibraryTrack(val ratingKey: String, val title: String, val artist: String?, val album: String?)

// --- Wire shapes -------------------------------------------------------------------------------
// Plex's JSON (`Accept: application/json`) wraps every response in one `MediaContainer`, whose
// `Metadata` array holds whatever was asked for - playlists, a playlist's items, or matching
// library tracks - shaped identically enough to share one DTO. `ignoreUnknownKeys` on the decoder
// (see PlexClient) stays on even though the read side (listPlaylists/playlistItems/
// findLibraryTracks) has now been verified field-by-field against a real authenticated server
// (2026-10-10/11, see docs/local-setup.md) - Plex's schema is still undocumented outside its
// community wiki, and this only ever modelled the fields this app actually reads, not the whole
// shape Plex sends.

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

/** `GET /identity` - unauthenticated even without a token (confirmed live), but [PlexClient]
 * always sends one anyway for consistency with every other call. */
@Serializable
internal data class PlexIdentityResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexIdentityContainer = PlexIdentityContainer(),
)

@Serializable
internal data class PlexIdentityContainer(val machineIdentifier: String? = null)

/** `GET /library/sections` - every library on the server, music included. */
@Serializable
internal data class PlexSectionsResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexSectionsContainer = PlexSectionsContainer(),
)

@Serializable
internal data class PlexSectionsContainer(
    @SerialName("Directory") val directory: List<PlexSectionDto> = emptyList(),
)

@Serializable
internal data class PlexSectionDto(val key: String? = null, val type: String? = null)

/**
 * `PUT /playlists/{id}/items` - unlike every other call here, the useful fields
 * (`leafCountAdded`/`leafCountRequested`) sit directly on `MediaContainer`, not inside a
 * `Metadata` row, confirmed live (2026-10-11): adding 2 known-good ratingKeys to a 3-rd-party test
 * playlist returned `leafCountAdded=2, leafCountRequested=2`.
 */
@Serializable
internal data class PlexAddItemsResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexAddItemsContainer = PlexAddItemsContainer(),
)

@Serializable
internal data class PlexAddItemsContainer(val leafCountAdded: Int? = null, val leafCountRequested: Int? = null)
