package com.denonmusic.heos

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HeosProtocolTest {

    @Test
    fun `escapes percent before the characters whose escapes introduce percents`() {
        // Naive ordering yields "%2526": the percent produced by escaping & gets escaped again.
        assertEquals("%2526", HeosProtocol.escape("%26"))
        assertEquals("%26", HeosProtocol.escape("&"))
        assertEquals("%3D", HeosProtocol.escape("="))
        assertEquals("%25", HeosProtocol.escape("%"))
    }

    @Test
    fun `escape and unescape round trip the awkward characters`() {
        val raw = "Rock & Roll = 100% Fun"
        assertEquals(raw, HeosProtocol.unescape(HeosProtocol.escape(raw)))
    }

    @Test
    fun `unescape does not manufacture escapes out of a decoded percent`() {
        // "%2526" must decode to the literal "%26", not to "&".
        assertEquals("%26", HeosProtocol.unescape("%2526"))
    }

    @Test
    fun `builds a command with attributes in the order given`() {
        val command = HeosProtocol.buildCommand(
            group = "browse",
            command = "add_to_queue",
            attributes = listOf("pid" to "1", "sid" to "1024", "cid" to "Artist/All", "aid" to "3"),
        )
        assertEquals("heos://browse/add_to_queue?pid=1&sid=1024&cid=Artist/All&aid=3", command)
    }

    @Test
    fun `forces url to be the last attribute`() {
        val command = HeosProtocol.buildCommand(
            group = "browse",
            command = "play_stream",
            attributes = listOf(HeosProtocol.URL to "http://host/a.flac", "pid" to "1"),
        )
        assertTrue(command.endsWith("url=http://host/a.flac"), "url must be last, was: $command")
    }

    @Test
    fun `omits the query entirely when there are no attributes`() {
        assertEquals("heos://system/heart_beat", HeosProtocol.buildCommand("system", "heart_beat"))
    }

    @Test
    fun `parses a response message into attributes`() {
        val parsed = HeosProtocol.parseMessage("sid=1024&returned=10&count=42")
        assertEquals(mapOf("sid" to "1024", "returned" to "10", "count" to "42"), parsed)
    }

    @Test
    fun `parses an escaped value back to its original form`() {
        val parsed = HeosProtocol.parseMessage("name=Rock%26Roll%3DFun")
        assertEquals("Rock&Roll=Fun", parsed["name"])
    }

    @Test
    fun `treats everything after url as part of the url`() {
        // A stream URL carries its own & and =, which is exactly why the spec pins it last.
        val parsed = HeosProtocol.parseMessage("pid=1&url=http://host/f?a=1&b=2")
        assertEquals("1", parsed["pid"])
        assertEquals("http://host/f?a=1&b=2", parsed["url"])
    }

    @Test
    fun `handles a bare flag with no value`() {
        val parsed = HeosProtocol.parseMessage("pid=1&signed_out")
        assertEquals("", parsed["signed_out"])
    }

    @Test
    fun `returns no attributes for a blank message`() {
        assertTrue(HeosProtocol.parseMessage("").isEmpty())
    }
}
