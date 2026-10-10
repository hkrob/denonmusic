package com.denonmusic.app.playlists

import com.denonmusic.data.search.SearchIndexEntity
import com.denonmusic.plex.PlexPlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [resolveTrack] is the one piece of [PlaylistRepository.resolve] that doesn't need a database or a
 * Plex server to pin - it's a pure decision over whatever [com.denonmusic.data.search.SearchIndexDao
 * .findTrack] already returned. The case that matters most here is [ResolvedTrack.Ambiguous]: a real,
 * not hypothetical, library has two different physical tracks with the same (artist, album, title) -
 * see docs/local-setup.md, 2026-10-10 - and queueing the wrong one silently is worse than not
 * queueing anything, so these tests exist to keep that true.
 */
class PlaylistRepositoryTest {

    private fun item(
        title: String = "Around the World in a Day",
        artist: String? = "Prince",
        album: String? = "Around the World in a Day",
    ) = PlexPlaylistItem(ratingKey = "1", title = title, artist = artist, album = album, durationMs = 208_346)

    private fun entity(queueCid: String) = SearchIndexEntity(
        name = "Around the World in a Day",
        nameLower = "around the world in a day",
        artist = "Prince",
        album = "Around the World in a Day",
        isContainer = false,
        isTrack = true,
        pathJson = "",
        queueSid = "1024",
        queueCid = queueCid,
        mid = null,
        indexedAt = 1L,
    )

    @Test
    fun `no candidates resolves to Unmatched`() {
        val result = resolveTrack(item(), emptyList())

        assertIs<ResolvedTrack.Unmatched>(result)
    }

    @Test
    fun `exactly one candidate resolves to Matched with its queue target`() {
        val result = resolveTrack(item(), listOf(entity("cid-1")))

        val matched = assertIs<ResolvedTrack.Matched>(result)
        assertEquals("cid-1", matched.target.cid)
    }

    @Test
    fun `two candidates resolve to Ambiguous, not an arbitrary pick`() {
        val result = resolveTrack(item(), listOf(entity("cid-1"), entity("cid-2")))

        val ambiguous = assertIs<ResolvedTrack.Ambiguous>(result)
        assertEquals(listOf("cid-1", "cid-2"), ambiguous.candidates.map { it.cid })
    }

    @Test
    fun `matchedTargets on a resolved playlist excludes ambiguous and unmatched tracks`() {
        val playlist = com.denonmusic.plex.PlexPlaylist(ratingKey = "9", title = "2026 Drive", itemCount = 3)
        val resolved = ResolvedPlaylist(
            playlist,
            listOf(
                resolveTrack(item("Solo match"), listOf(entity("cid-solo"))),
                resolveTrack(item("Ambiguous one"), listOf(entity("cid-a"), entity("cid-b"))),
                resolveTrack(item("Missing"), emptyList()),
            ),
        )

        // Only the one unambiguous match reaches a bulk "Play All"/"Add All" - an ambiguous track
        // must never be guessed at just because it's in a batch rather than tapped individually.
        assertEquals(listOf("cid-solo"), resolved.matchedTargets.map { it.cid })
    }
}
