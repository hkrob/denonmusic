package com.denonmusic.heos

import kotlin.test.Test
import kotlin.test.assertEquals

class HeosModelsTest {

    @Test
    fun `decodes the five predefined XML entities`() {
        assertEquals("Girls & Boys", xmlUnescape("Girls &amp; Boys"))
        assertEquals("<tag>", xmlUnescape("&lt;tag&gt;"))
        assertEquals("\"quoted\" it's", xmlUnescape("&quot;quoted&quot; it&apos;s"))
    }

    @Test
    fun `decodes decimal and hex numeric character references`() {
        assertEquals("café", xmlUnescape("caf&#233;"))
        assertEquals("café", xmlUnescape("caf&#xE9;"))
    }

    @Test
    fun `leaves plain text and an unescaped ampersand alone`() {
        assertEquals("Rock & Roll", xmlUnescape("Rock & Roll"))
        assertEquals("No entities here", xmlUnescape("No entities here"))
    }

    @Test
    fun `leaves an unrecognised entity as-is rather than guessing`() {
        assertEquals("&notareal;", xmlUnescape("&notareal;"))
        assertEquals("&#zzz;", xmlUnescape("&#zzz;"))
    }
}
