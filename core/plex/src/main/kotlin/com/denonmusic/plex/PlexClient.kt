package com.denonmusic.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thrown for something the Plex server itself reported going wrong - a non-2xx response, most often
 * a bad or missing token (401) or an unknown playlist (404 after it's been deleted elsewhere). A
 * network-level failure (host unreachable, timeout) surfaces as the underlying [IOException]
 * instead - the same split [com.denonmusic.heos.HeosCommandException] draws from a dropped socket
 * in `:core:heos`.
 */
class PlexApiException(message: String, val statusCode: Int) : RuntimeException(message)

/**
 * Thin client over Plex Media Server's own HTTP API (default port 32400) - a second control channel
 * alongside HEOS, not a replacement for it. HEOS/DLNA cannot see Plex playlists at all: probed live
 * against this project's receiver and Plex server (2026-10-10), the DLNA tree Plex itself exposes
 * stops at `Video`/`Music`/`Photos`, and the Music library's own eight views (`All Artists`/
 * `By Album`/.../`By Folder` - see `docs/local-setup.md`) have no `Playlists` anywhere among them.
 * So reading *or writing* a Plex playlist has to go straight to Plex; playing one still goes through
 * real HEOS (see the app's `PlaylistRepository`, which is where the two are joined back together) -
 * the same invariant every other playback path in this app follows. Writing one's *contents* has no
 * such invariant to honour - adding a track to a Plex playlist is a Plex-side metadata change, not
 * playback, so it is just an HTTP call with no HEOS counterpart at all.
 *
 * Uses [HttpURLConnection], not `java.net.http.HttpClient`: the latter only exists on Android from
 * API 34, far above this app's `minSdk` 26, so reaching for it here would crash on install on most
 * phones it's meant to run on despite compiling fine against `compileSdk`. `HttpURLConnection` has
 * been available since API 1 - the same "plain `java.net`, nothing fancier" choice `:core:heos`'s
 * `HeosConnection` already makes with a raw `Socket`.
 *
 * Auth is one header, `X-Plex-Token` - confirmed live that `/identity` answers unauthenticated but
 * `/playlists` 401s without it. There is no token-acquisition flow here (no plex.tv sign-in, no PIN
 * linking): the token is pasted into Settings, the same way every third-party Plex client gets one
 * (Plex web app -> any item -> Get Info -> View XML; the token is in that page's own URL).
 */
class PlexClient(private val baseUrl: String, private val token: String) {

    suspend fun listPlaylists(): List<PlexPlaylist> {
        val response = request<PlexResponse>("GET", "/playlists?playlistType=audio")
        return response.mediaContainer.metadata.mapNotNull { dto ->
            val ratingKey = dto.ratingKey ?: return@mapNotNull null
            val title = dto.title ?: return@mapNotNull null
            PlexPlaylist(ratingKey = ratingKey, title = title, itemCount = dto.leafCount ?: 0)
        }
    }

    suspend fun playlistItems(playlistRatingKey: String): List<PlexPlaylistItem> {
        val response = request<PlexResponse>("GET", "/playlists/$playlistRatingKey/items")
        return response.mediaContainer.metadata.mapNotNull { dto ->
            val ratingKey = dto.ratingKey ?: return@mapNotNull null
            val title = dto.title ?: return@mapNotNull null
            PlexPlaylistItem(
                ratingKey = ratingKey,
                title = title,
                artist = dto.grandparentTitle,
                album = dto.parentTitle,
                durationMs = dto.duration,
            )
        }
    }

    /**
     * The server's own id, needed to build the `server://<id>/com.plexapp.plugins.library/...` URI
     * every add/create call below addresses a track by - confirmed live (2026-10-10) that this
     * answers even unauthenticated, though this client always sends the token anyway for consistency.
     */
    suspend fun identity(): String {
        val response = request<PlexIdentityResponse>("GET", "/identity")
        return response.mediaContainer.machineIdentifier
            ?: throw PlexApiException("Plex's /identity response had no machineIdentifier", 0)
    }

    /**
     * The music library's own section key (confirmed live: `3`, `type="artist"` on this project's
     * server - Plex's own term for a music library, inherited from "artist" being its top-level
     * grouping). Needed by [findLibraryTracks]; there is exactly one such section on every Plex
     * server this app has reason to expect, so the first one found is used without disambiguating
     * between several.
     */
    suspend fun musicSectionKey(): String {
        val response = request<PlexSectionsResponse>("GET", "/library/sections")
        val section = response.mediaContainer.directory.firstOrNull { it.type == "artist" }
            ?: throw PlexApiException("No music library found on this Plex server", 0)
        return section.key ?: throw PlexApiException("Plex's music library section has no key", 0)
    }

    /**
     * Tracks in [sectionKey] whose title contains [title] - confirmed live (2026-10-10) that Plex's
     * own `title=` filter here is a substring/"contains" match, not exact (searching "Manic Monday"
     * returned "Manic Monday (Unreleased)" and five other variants alongside the plain title) - so
     * the caller narrows to an exact match itself; see the app's `PlaylistRepository`.
     */
    suspend fun findLibraryTracks(sectionKey: String, title: String): List<PlexLibraryTrack> {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val response = request<PlexResponse>("GET", "/library/sections/$sectionKey/all?type=10&title=$encodedTitle")
        return response.mediaContainer.metadata.mapNotNull { dto ->
            val ratingKey = dto.ratingKey ?: return@mapNotNull null
            val dtoTitle = dto.title ?: return@mapNotNull null
            PlexLibraryTrack(ratingKey = ratingKey, title = dtoTitle, artist = dto.grandparentTitle, album = dto.parentTitle)
        }
    }

    /**
     * Adds every one of [itemRatingKeys] to [playlistRatingKey] in a single call - confirmed live
     * (2026-10-11) that Plex accepts several comma-joined ratingKeys in one `uri`, returning
     * `leafCountAdded` for exactly how many actually landed (an item already in the playlist is not
     * added twice, and would make this number smaller than `itemRatingKeys.size` with no error).
     * [machineId] comes from [identity] - the caller fetches it once and passes it in, rather than
     * this method fetching it itself, since a caller adding many tracks (`PlaylistRepository`
     * creating a playlist from a whole queue) would otherwise pay for it on every single track found.
     */
    suspend fun addToPlaylist(playlistRatingKey: String, machineId: String, itemRatingKeys: List<String>): Int {
        require(itemRatingKeys.isNotEmpty()) { "Nothing to add" }
        val encodedUri = URLEncoder.encode(metadataUri(machineId, itemRatingKeys), "UTF-8")
        val response = request<PlexAddItemsResponse>("PUT", "/playlists/$playlistRatingKey/items?uri=$encodedUri")
        return response.mediaContainer.leafCountAdded ?: 0
    }

    /**
     * Creates a new playlist titled [title] seeded with [itemRatingKeys] - confirmed live
     * (2026-10-11) against a throwaway test playlist, created then deleted in the same session, that
     * this is the same `uri`-with-comma-joined-ratingKeys shape [addToPlaylist] uses, just on
     * `POST /playlists` instead of `PUT .../items`. See [addToPlaylist] for why [machineId] is a
     * parameter rather than fetched here.
     */
    suspend fun createPlaylist(title: String, machineId: String, itemRatingKeys: List<String>): PlexPlaylist {
        require(itemRatingKeys.isNotEmpty()) { "Nothing to create the playlist with" }
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedUri = URLEncoder.encode(metadataUri(machineId, itemRatingKeys), "UTF-8")
        val response = request<PlexResponse>("POST", "/playlists?type=audio&smart=0&title=$encodedTitle&uri=$encodedUri")
        val dto = response.mediaContainer.metadata.firstOrNull()
            ?: throw PlexApiException("Plex didn't return the playlist it just created", 0)
        val ratingKey = dto.ratingKey ?: throw PlexApiException("Plex's created playlist had no ratingKey", 0)
        return PlexPlaylist(ratingKey = ratingKey, title = dto.title ?: title, itemCount = dto.leafCount ?: itemRatingKeys.size)
    }

    private fun metadataUri(machineId: String, ratingKeys: List<String>) =
        "server://$machineId/com.plexapp.plugins.library/library/metadata/${ratingKeys.joinToString(",")}"

    private suspend inline fun <reified T> request(method: String, path: String): T = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("X-Plex-Token", token)
        connection.setRequestProperty("Accept", "application/json")
        // Identifies this app in Plex's own UI (its "devices" list under an account), not a security
        // boundary - one fixed value per app is the normal thing for a client to send, same as every
        // other third-party Plex client does.
        connection.setRequestProperty("X-Plex-Client-Identifier", CLIENT_IDENTIFIER)
        connection.setRequestProperty("X-Plex-Product", "DenonMusic")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw PlexApiException("Plex returned HTTP $status for $path", status)
            json.decodeFromString(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val CLIENT_IDENTIFIER = "denonmusic-android"
        val json = Json { ignoreUnknownKeys = true }
    }
}
