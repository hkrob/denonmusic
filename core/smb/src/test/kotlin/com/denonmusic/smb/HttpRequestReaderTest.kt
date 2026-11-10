package com.denonmusic.smb

import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestReaderTest {

    private fun reader(text: String, maxLineChars: Int = 16, maxTotalChars: Int = 64) =
        HttpRequestReader(text.byteInputStream(Charsets.US_ASCII), maxLineChars, maxTotalChars)

    @Test
    fun `reads CRLF and bare LF lines, then null at end of stream`() {
        val r = reader("GET / HTTP/1.1\r\nHost: x\n")

        assertEquals("GET / HTTP/1.1", r.readLine())
        assertEquals("Host: x", r.readLine())
        assertNull(r.readLine())
        assertFalse(r.overflowed)
    }

    @Test
    fun `a final line with no terminator is still returned`() {
        val r = reader("abc")

        assertEquals("abc", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun `an over-long line is consumed to its end, cut short, and flagged`() {
        val r = reader("a".repeat(40) + "\r\nnext\r\n")

        assertEquals("a".repeat(16), r.readLine())
        assertTrue(r.overflowed)
        // The rest of the long line was discarded, not left to be misread as the next one.
        assertEquals("next", r.readLine())
    }

    @Test
    fun `a line of exactly the limit is not an overflow`() {
        val r = reader("a".repeat(16) + "\r\n")

        assertEquals("a".repeat(16), r.readLine())
        assertFalse(r.overflowed)
    }

    @Test
    fun `a request past the total limit is abandoned`() {
        val r = reader("a".repeat(200))

        assertFailsWith<IOException> { r.readLine() }
    }

    @Test
    fun `headers are lower-cased, trimmed, and stop at the blank line`() {
        val r = reader("Range:  bytes=0-1 \r\nnonsense\r\nX-A: b\r\n\r\nbody", maxLineChars = 64, maxTotalChars = 256)

        assertEquals(mapOf("range" to "bytes=0-1", "x-a" to "b"), r.readHeaders())
        assertEquals("body", r.readLine())
    }
}
