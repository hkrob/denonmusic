package com.denonmusic.server

import com.denonmusic.server.SearchRanking.Kind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRankingTest {

    private fun folder(name: String, context: String = "") =
        IndexedEntry(name = name, kind = Kind.Container, sid = "1", cid = name, context = context)

    private fun track(name: String, artist: String? = null, album: String? = null) = IndexedEntry(
        name = name,
        artist = artist,
        album = album,
        kind = Kind.Track,
        sid = "1",
        cid = "c",
        mid = name,
        context = listOfNotNull(artist, album).joinToString(" "),
    )

    private fun best(entries: List<IndexedEntry>, query: String, artist: String? = null, kind: Kind? = null) =
        SearchRanking.rank(entries, query, artist, kind).firstOrNull()?.entry?.name

    @Test
    fun `normalizing folds case, accents and punctuation`() {
        assertEquals("bjork debut 1993", SearchRanking.normalize("Björk - Début (1993)"))
        assertEquals("guns n roses", SearchRanking.normalize("Guns N' Roses"))
        assertEquals("simon and garfunkel", SearchRanking.normalize("Simon & Garfunkel"))
    }

    @Test
    fun `an exact name beats a longer name containing it`() {
        val entries = listOf(folder("Moonlight Sonata and Other Moon Pieces"), folder("Moon Safari"), folder("Moon"))

        assertEquals("Moon", best(entries, "moon"))
    }

    @Test
    fun `articles a recogniser drops or adds do not decide the match`() {
        val entries = listOf(folder("The Dark Side of the Moon"), folder("Dark Matter"))

        assertEquals("The Dark Side of the Moon", best(entries, "dark side of moon"))
        assertEquals("The Dark Side of the Moon", best(entries, "the dark side of the moon"))
    }

    @Test
    fun `finds a folder whose name wraps the title in an artist and a year`() {
        val entries = listOf(folder("Pink Floyd - The Wall (1979)"), folder("Wallflowers"))

        assertEquals("Pink Floyd - The Wall (1979)", best(entries, "the wall"))
    }

    @Test
    fun `an album is preferred to its title track unless a track was asked for`() {
        val entries = listOf(track("Thriller", "Michael Jackson", "Thriller"), folder("Thriller", "Michael Jackson"))

        assertEquals(Kind.Container, SearchRanking.rank(entries, "thriller").first().entry.kind)
        assertEquals(Kind.Track, SearchRanking.rank(entries, "thriller", kind = Kind.Track).first().entry.kind)
    }

    @Test
    fun `an artist is preferred to a self-titled album, and either to a folder of the same name`() {
        val album = IndexedEntry(name = "Prince", artist = "Prince", kind = Kind.Album, sid = "1", context = "Prince")
        val artist = IndexedEntry(name = "Prince", kind = Kind.Artist, sid = "1")
        val entries = listOf(folder("Prince"), album, artist)

        assertEquals(listOf(Kind.Artist, Kind.Album, Kind.Container), SearchRanking.rank(entries, "prince").map { it.entry.kind })
        assertEquals(Kind.Album, SearchRanking.rank(entries, "prince", kind = Kind.Album).single().entry.kind)
    }

    @Test
    fun `a named artist picks between two things with the same title`() {
        val entries = listOf(track("Hurt", "Nine Inch Nails"), track("Hurt", "Johnny Cash"))

        assertEquals("Johnny Cash", SearchRanking.rank(entries, "hurt", artist = "johnny cash").first().entry.artist)
        assertEquals("Nine Inch Nails", SearchRanking.rank(entries, "hurt", artist = "nine inch nails").first().entry.artist)
    }

    @Test
    fun `a query may name the artist alongside the title`() {
        val entries = listOf(track("Money", "Pink Floyd"), track("Money", "The Beatles"), track("Money for Nothing", "Dire Straits"))

        assertEquals("Pink Floyd", SearchRanking.rank(entries, "money pink floyd").first().entry.artist)
    }

    @Test
    fun `an album title alone does not match every track on the album`() {
        val entries = listOf(track("Speak to Me", "Pink Floyd", "The Dark Side of the Moon"))

        assertTrue(SearchRanking.rank(entries, "dark side of the moon").isEmpty())
    }

    @Test
    fun `nothing is returned for a query that matches nothing or is blank`() {
        val entries = listOf(folder("Moon"))

        assertTrue(SearchRanking.rank(entries, "zeppelin").isEmpty())
        assertTrue(SearchRanking.rank(entries, "  ").isEmpty())
    }
}
