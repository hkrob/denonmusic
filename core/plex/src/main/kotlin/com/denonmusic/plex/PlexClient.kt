package com.denonmusic.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
 * So reading a Plex playlist has to go straight to Plex; queueing it still goes through real HEOS
 * (see the app's `PlaylistRepository`, which is where the two are joined back together) - the same
 * invariant every other playback path in this app follows.
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
        val response = get("/playlists?playlistType=audio")
        return response.mediaContainer.metadata.mapNotNull { dto ->
            val ratingKey = dto.ratingKey ?: return@mapNotNull null
            val title = dto.title ?: return@mapNotNull null
            PlexPlaylist(ratingKey = ratingKey, title = title, itemCount = dto.leafCount ?: 0)
        }
    }

    suspend fun playlistItems(playlistRatingKey: String): List<PlexPlaylistItem> {
        val response = get("/playlists/$playlistRatingKey/items")
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

    private suspend fun get(path: String): PlexResponse = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
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
            json.decodeFromString(PlexResponse.serializer(), body)
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
