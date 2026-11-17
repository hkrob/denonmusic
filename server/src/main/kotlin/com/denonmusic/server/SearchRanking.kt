package com.denonmusic.server

import java.text.Normalizer

/**
 * Ranks index entries against a spoken query.
 *
 * The app's own search is a substring match sorted by name, which is right for a list a person
 * scrolls and wrong for a voice command, where one answer gets played and the rest are never seen.
 * So this scores how *well* a name matches, not just whether it does.
 *
 * Four kinds of thing can be found. [Kind.Container] and [Kind.Track] are what the crawl saw: a
 * folder, a file. [Kind.Album] and [Kind.Artist] are derived from the tracks' own tags - see
 * [LibraryIndex.derive] for why the folders alone were not enough on a real library.
 */
object SearchRanking {

    enum class Kind { Container, Track, Album, Artist }

    /**
     * Added when no kind was asked for, to settle a name that several kinds share. "Play Prince"
     * means the artist, not his self-titled album; "play Thriller" means the album, not its title
     * track; and a tagged album or artist is a better answer than a folder that happens to be named
     * the same, since a real library has the same folder name in more than one place.
     */
    private val KIND_PREFERENCE = mapOf(Kind.Artist to 4, Kind.Album to 3, Kind.Container to 2, Kind.Track to 0)

    data class Scored(val entry: IndexedEntry, val score: Int)

    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{M}+")

    /** Words a speech recogniser adds or drops freely, so they must not decide a match. */
    private val STOP_WORDS = setOf("the", "a", "an", "by", "of", "and")

    /** Lowercased, accents folded, punctuation to spaces: "Björk - Début (1993)" -> "bjork debut 1993". */
    fun normalize(text: String): String =
        DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
            .lowercase()
            .replace("&", " and ")
            .replace("'", "")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    private fun tokens(normalized: String): List<String> = normalized.split(' ').filter { it.isNotEmpty() }

    private fun significant(tokens: List<String>): List<String> =
        tokens.filterNot { it in STOP_WORDS }.ifEmpty { tokens }

    fun rank(
        entries: List<IndexedEntry>,
        query: String,
        artist: String? = null,
        kind: Kind? = null,
        limit: Int = 10,
    ): List<Scored> {
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return emptyList()
        val queryTokens = significant(tokens(queryNorm))
        val artistTokens = artist?.let { significant(tokens(normalize(it))) }.orEmpty()

        return entries.asSequence()
            .filter { kind == null || it.kind == kind }
            .mapNotNull { entry -> score(entry, queryNorm, queryTokens, artistTokens, kind)?.let { Scored(entry, it) } }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.entry.name.length })
            .take(limit)
            .toList()
    }

    private fun score(
        entry: IndexedEntry,
        queryNorm: String,
        queryTokens: List<String>,
        artistTokens: List<String>,
        kind: Kind?,
    ): Int? {
        val nameTokens = tokens(entry.nameNorm)
        val nameSignificant = significant(nameTokens)
        val contextTokens = tokens(entry.contextNorm)

        val base = when {
            entry.nameNorm == queryNorm -> 100
            nameSignificant == queryTokens -> 95
            entry.nameNorm.startsWith("$queryNorm ") -> 80
            " ${entry.nameNorm} ".contains(" $queryNorm ") -> 70
            nameTokens.containsAll(queryTokens) -> 60
            // "dark side of the moon pink floyd": the query names the thing and its context at once.
            // At least one word has to land in the name itself, or every track on an album would
            // match a query that is just the album's title.
            queryTokens.any { it in nameTokens } && (nameTokens + contextTokens).containsAll(queryTokens) -> 45
            else -> return null
        }

        var score = base
        // A name much longer than what was asked for is a weaker match than a tight one, whatever
        // tier it landed in: "Moon" should prefer the album "Moon" to "Moonlight Sonata (Live)".
        score -= (nameSignificant.size - queryTokens.size).coerceIn(0, 8)
        if (artistTokens.isNotEmpty()) {
            score += if ((contextTokens + nameTokens).containsAll(artistTokens)) 15 else -20
        }
        if (kind == null) score += KIND_PREFERENCE.getValue(entry.kind)
        return score
    }
}
