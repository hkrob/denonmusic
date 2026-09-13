package com.denonmusic.smb

import java.io.ByteArrayInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ArtworkExtractorTest {

    private val jpegLike = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5)

    @Test
    fun `extracts a FLAC PICTURE block's image bytes`() {
        val bytes = TestFixtures.flacWithPicture(jpegLike)

        val picture = ArtworkExtractor.extractFlacPicture(ByteArrayInputStream(bytes))

        assertContentEquals(jpegLike, picture)
    }

    @Test
    fun `returns null for a FLAC file with no PICTURE block`() {
        val bytes = TestFixtures.flacStreamInfo(44_100, 2, 16)

        val picture = ArtworkExtractor.extractFlacPicture(ByteArrayInputStream(bytes))

        assertNull(picture)
    }

    @Test
    fun `extracts an ID3v2_3 APIC frame's image bytes`() {
        val bytes = TestFixtures.id3WithApic(jpegLike)

        val picture = ArtworkExtractor.extractId3Apic(ByteArrayInputStream(bytes))

        assertContentEquals(jpegLike, picture)
    }

    @Test
    fun `returns null when there is no ID3 tag at all`() {
        val picture = ArtworkExtractor.extractId3Apic(ByteArrayInputStream(ByteArray(20)))

        assertNull(picture)
    }
}
