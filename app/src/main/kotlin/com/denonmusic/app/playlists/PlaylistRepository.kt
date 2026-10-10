package com.denonmusic.app.playlists

import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.QueueTarget
import com.denonmusic.plex.PlexClient
import com.denonmusic.plex.PlexPlaylist
import com.denonmusic.plex.PlexPlaylistItem
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

class PlexNotConfiguredException : RuntimeException("Set the Plex server address and token in Settings first")

/** One playlist track, resolved against the local search index or not. */
sealed interface ResolvedTrack {
    val item: PlexPlaylistItem

    data class Matched(override val item: PlexPlaylistItem, val target: QueueTarget) : ResolvedTrack
    data class Unmatched(override val item: PlexPlaylistItem) : ResolvedTrack
}

data class ResolvedPlaylist(val playlist: PlexPlaylist, val tracks: List<ResolvedTrack>) {
    val matchedTargets: List<QueueTarget> get() = tracks.filterIsInstance<ResolvedTrack.Matched>().map { it.target }
}

/**
 * Bridges Plex's own playlists to this app's existing queueing path. HEOS/DLNA cannot see Plex
 * playlists at all (see `:core:plex`'s `PlexClient` doc) - Plex's HTTP API is the only way to read
 * one - but playing it still has to go through real HEOS, the same invariant everything else in
 * this app follows: the phone is never in the audio path. So each Plex item (artist/album/title) is
 * matched against [SearchIndexDao] - built by the existing Search feature's crawl of the same
 * library - to recover the `sid/cid/mid` HEOS needs to queue it. See [SearchIndexDao.findTrack] for
 * what the match is (exact, case-insensitive, no duration tie-break) and what it gets wrong.
 *
 * A Search index that has never been built, or is stale against what Plex has now, means every
 * track looks unmatched - this repository does not trigger a reindex itself, since that is a
 * multi-minute foreground-service operation (`SearchIndexService`) with its own UI on the Search
 * tab, not something to kick off silently from here.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val searchIndexDao: SearchIndexDao,
) {

    private suspend fun client(): PlexClient {
        val current = settings.settings.first()
        val baseUrl = current.plexBaseUrl?.takeIf { it.isNotBlank() } ?: throw PlexNotConfiguredException()
        val token = current.plexToken?.takeIf { it.isNotBlank() } ?: throw PlexNotConfiguredException()
        return PlexClient(baseUrl, token)
    }

    suspend fun listPlaylists(): Result<List<PlexPlaylist>> = runCatching { client().listPlaylists() }

    suspend fun resolve(playlist: PlexPlaylist): Result<ResolvedPlaylist> = runCatching {
        val items = client().playlistItems(playlist.ratingKey)
        val tracks = items.map { item ->
            val entity = searchIndexDao.findTrack(item.title.lowercase(), item.artist, item.album).firstOrNull()
            if (entity == null) {
                ResolvedTrack.Unmatched(item)
            } else {
                ResolvedTrack.Matched(item, QueueTarget(entity.queueSid, entity.queueCid, entity.mid))
            }
        }
        ResolvedPlaylist(playlist, tracks)
    }
}
