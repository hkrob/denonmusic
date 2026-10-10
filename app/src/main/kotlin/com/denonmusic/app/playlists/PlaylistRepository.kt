package com.denonmusic.app.playlists

import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.search.SearchIndexEntity
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.QueueTarget
import com.denonmusic.plex.PlexClient
import com.denonmusic.plex.PlexPlaylist
import com.denonmusic.plex.PlexPlaylistItem
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

class PlexNotConfiguredException : RuntimeException("Set the Plex server address and token in Settings first")

/** One playlist track, resolved against the local search index, ambiguously, or not at all. */
sealed interface ResolvedTrack {
    val item: PlexPlaylistItem

    data class Matched(override val item: PlexPlaylistItem, val target: QueueTarget) : ResolvedTrack

    /**
     * More than one indexed track matched the same (artist, album, title) with nothing left to break
     * the tie on - HEOS's own `browse/browse` never reports a track's duration or any other
     * distinguishing field (see `BrowseItem` in `:core:heos`), so there is no signal available here
     * to pick automatically, and picking the wrong one silently is worse than not picking at all:
     * confirmed a real, recurring case against a real library, not a hypothetical edge case (two
     * different physical files both titled "Around the World in a Day" by Prince on the same-titled
     * album - see docs/local-setup.md, 2026-10-10). Deliberately excluded from
     * [ResolvedPlaylist.matchedTargets] so a bulk "Play All"/"Add All" never guesses; the one thing
     * this app can offer instead is saying so plainly rather than picking silently.
     */
    data class Ambiguous(override val item: PlexPlaylistItem, val candidates: List<QueueTarget>) : ResolvedTrack
    data class Unmatched(override val item: PlexPlaylistItem) : ResolvedTrack
}

/**
 * The one piece of [PlaylistRepository.resolve] worth testing without a database: given every
 * [SearchIndexDao.findTrack] candidate for one Plex item, which [ResolvedTrack] that is.
 */
internal fun resolveTrack(item: PlexPlaylistItem, candidates: List<SearchIndexEntity>): ResolvedTrack = when (candidates.size) {
    0 -> ResolvedTrack.Unmatched(item)
    1 -> ResolvedTrack.Matched(item, candidates[0].toQueueTarget())
    else -> ResolvedTrack.Ambiguous(item, candidates.map { it.toQueueTarget() })
}

private fun SearchIndexEntity.toQueueTarget() = QueueTarget(queueSid, queueCid, mid)

data class ResolvedPlaylist(val playlist: PlexPlaylist, val tracks: List<ResolvedTrack>) {
    val matchedTargets: List<QueueTarget> get() = tracks.filterIsInstance<ResolvedTrack.Matched>().map { it.target }
}

/**
 * Bridges Plex's own playlists to this app's existing queueing path. HEOS/DLNA cannot see Plex
 * playlists at all (see `:core:plex`'s `PlexClient` doc) - Plex's HTTP API is the only way to read
 * one - but playing it still has to go through real HEOS, the same invariant everything else in
 * this app follows: the phone is never in the audio path. So each Plex item (artist/album/title) is
 * matched against [SearchIndexDao] - built by the existing Search feature's crawl of the same
 * library - to recover the `sid/cid/mid` HEOS needs to queue it. The match itself is exact,
 * case-insensitive, on (artist, album, title) - see [SearchIndexDao.findTrack]. More than one hit is
 * a real, not hypothetical, possibility (see [ResolvedTrack.Ambiguous]), handled by not guessing.
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
            resolveTrack(item, searchIndexDao.findTrack(item.title.lowercase(), item.artist, item.album))
        }
        ResolvedPlaylist(playlist, tracks)
    }
}
