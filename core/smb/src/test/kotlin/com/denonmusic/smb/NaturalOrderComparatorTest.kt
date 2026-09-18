package com.denonmusic.smb

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the ordering bug found testing [SmbOverlay.listDirectory] against a real
 * share: a 10-disc box set (`Classical/Kronos Quartet - 1998 - 25 Years`) has unpadded `Disc 1` ..
 * `Disc 10` subfolders, which a plain string sort puts as `Disc 1, Disc 10, Disc 2, ..., Disc 9` -
 * wrong play order for `SmbOverlay.listFilesRecursive`'s multi-disc "play all".
 */
class NaturalOrderComparatorTest {

    @Test
    fun `disc folders sort numerically, not lexicographically`() {
        val discs = listOf("Disc 1", "Disc 10", "Disc 2", "Disc 3", "Disc 4", "Disc 5", "Disc 6", "Disc 7", "Disc 8", "Disc 9")
        val sorted = discs.sortedWith(naturalOrderComparator)
        assertEquals(
            listOf("Disc 1", "Disc 2", "Disc 3", "Disc 4", "Disc 5", "Disc 6", "Disc 7", "Disc 8", "Disc 9", "Disc 10"),
            sorted,
        )
    }

    @Test
    fun `zero-padded track names still sort correctly`() {
        val tracks = listOf("10 - Track.flac", "02 - Track.flac", "01 - Track.flac", "09 - Track.flac")
        val sorted = tracks.sortedWith(naturalOrderComparator)
        assertEquals(
            listOf("01 - Track.flac", "02 - Track.flac", "09 - Track.flac", "10 - Track.flac"),
            sorted,
        )
    }

    @Test
    fun `case insensitive and non-numeric names unaffected`() {
        val names = listOf("banana", "Apple", "cherry")
        val sorted = names.sortedWith(naturalOrderComparator)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted)
    }

    @Test
    fun `all-zero digit runs compare equal without crashing`() {
        assertEquals(0, naturalCompare("disc 0", "disc 00"))
    }
}
