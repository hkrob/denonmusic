package com.denonmusic.app.playlists

import com.denonmusic.data.search.SearchIndexDao
import com.denonmusic.data.search.SearchIndexEntity
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.QueueTarget
import com.denonmusic.plex.PlexClient
import com.denonmusic.plex.PlexLibraryTrack
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
 * Enough of a track (name/artist/album) to look it up in Plex's own library, for adding it to - or
 * seeding the creation of - a Plex playlist. The reverse direction from [ResolvedTrack]: there, a
 * Plex item is matched against the HEOS-side [SearchIndexDao]; here, a HEOS-side track (from Search,
 * Browse or the Queue) is matched against Plex's own library via [PlexClient.findLibraryTracks].
 */
data class PlexLookupTrack(val name: String, val artist: String?, val album: String?)

/** What came of trying to find [PlexLookupTrack] in Plex's library - see [resolveLibraryTrack]. */
internal sealed interface PlexTrackLookup {
    data class Found(val ratingKey: String) : PlexTrackLookup
    data object Ambiguous : PlexTrackLookup
    data object NotFound : PlexTrackLookup
}

/**
 * The one piece of the add-to-playlist path worth testing without a server: given every candidate
 * [PlexClient.findLibraryTracks] returned (a substring match on title - see that method's own doc),
 * which one, if any, is actually [track] - narrowed to an exact (case-insensitive) title match, then
 * by artist/album when [track] has them, same shape [resolveTrack] already uses in the other
 * direction.
 */
internal fun resolveLibraryTrack(track: PlexLookupTrack, candidates: List<PlexLibraryTrack>): PlexTrackLookup {
    val exact = candidates.filter { candidate ->
        candidate.title.equals(track.name, ignoreCase = true) &&
            (track.artist == null || candidate.artist?.equals(track.artist, ignoreCase = true) == true) &&
            (track.album == null || candidate.album?.equals(track.album, ignoreCase = true) == true)
    }
    return when (exact.size) {
        0 -> PlexTrackLookup.NotFound
        1 -> PlexTrackLookup.Found(exact[0].ratingKey)
        else -> PlexTrackLookup.Ambiguous
    }
}

/** What came of trying to add one track to an existing Plex playlist. */
sealed interface AddToPlaylistOutcome {
    data object Added : AddToPlaylistOutcome
    data object Ambiguous : AddToPlaylistOutcome
    data object NotFoundInPlex : AddToPlaylistOutcome
}

class NoMatchingTracksException : RuntimeException("None of these tracks were found in your Plex library")

/** [addedCount] of [totalCount] tracks actually made it in - fewer than [totalCount] when some
 * couldn't be found in Plex, or matched more than one track there and were skipped rather than
 * guessed at (see [resolveLibraryTrack]). */
data class CreatePlaylistOutcome(val playlist: PlexPlaylist, val addedCount: Int, val totalCount: Int)

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

    /** Adds [track] to [playlist] - see [AddToPlaylistOutcome] for the three ways this can end,
     * none of which is "added the wrong track silently". */
    suspend fun addTrackToPlaylist(playlist: PlexPlaylist, track: PlexLookupTrack): Result<AddToPlaylistOutcome> = runCatching {
        val plex = client()
        val sectionKey = plex.musicSectionKey()
        when (val lookup = resolveLibraryTrack(track, plex.findLibraryTracks(sectionKey, track.name))) {
            is PlexTrackLookup.Found -> {
                plex.addToPlaylist(playlist.ratingKey, plex.identity(), listOf(lookup.ratingKey))
                AddToPlaylistOutcome.Added
            }
            PlexTrackLookup.Ambiguous -> AddToPlaylistOutcome.Ambiguous
            PlexTrackLookup.NotFound -> AddToPlaylistOutcome.NotFoundInPlex
        }
    }

    /**
     * Creates a new Plex playlist titled [title] from [tracks] - typically the current HEOS queue
     * (see `PlayerViewModel.saveQueueAsPlexPlaylist`, the Plex-side sibling of the HEOS-native
     * `saveQueueAsPlaylist` this app already had). Looks every track up individually (one Plex
     * library search each - [identity]/[musicSectionKey] are each fetched once for the whole call,
     * not once per track, see [PlexClient.addToPlaylist]'s own doc on why that matters) and creates
     * the playlist with whichever ones resolved to exactly one match, in one call. Fails outright
     * only if *none* matched - a partial match still makes a real, if incomplete, playlist, which
     * [CreatePlaylistOutcome] reports honestly rather than silently.
     */
    suspend fun createPlaylistFromTracks(title: String, tracks: List<PlexLookupTrack>): Result<CreatePlaylistOutcome> = runCatching {
        val plex = client()
        val sectionKey = plex.musicSectionKey()
        val ratingKeys = tracks.mapNotNull { track ->
            (resolveLibraryTrack(track, plex.findLibraryTracks(sectionKey, track.name)) as? PlexTrackLookup.Found)?.ratingKey
        }
        if (ratingKeys.isEmpty()) throw NoMatchingTracksException()
        val playlist = plex.createPlaylist(title, plex.identity(), ratingKeys)
        CreatePlaylistOutcome(playlist, ratingKeys.size, tracks.size)
    }
}
