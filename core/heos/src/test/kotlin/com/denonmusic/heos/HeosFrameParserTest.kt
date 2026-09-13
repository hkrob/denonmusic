package com.denonmusic.heos

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HeosFrameParserTest {

    @Test
    fun `parses a successful response`() {
        val frame = HeosFrameParser.parse(
            """{"heos":{"command":"player/get_play_state","result":"success","message":"pid=1&state=play"}}""",
        )
        assertNotNull(frame)
        assertEquals("player/get_play_state", frame.command)
        assertTrue(frame.isSuccess)
        assertFalse(frame.isEvent)
        assertEquals("play", frame.attributes["state"])
    }

    @Test
    fun `trims the padding the receiver puts around some command names`() {
        val frame = HeosFrameParser.parse(
            """{"heos":{"command":" browse/add_to_queue ","result":"success","message":"pid=1"}}""",
        )
        assertEquals("browse/add_to_queue", frame?.command)
    }

    @Test
    fun `recognises an event and exposes its short name`() {
        val frame = HeosFrameParser.parse(
            """{"heos":{"command":"event/player_state_changed","message":"pid=1&state=pause"}}""",
        )
        assertNotNull(frame)
        assertTrue(frame.isEvent)
        assertEquals("player_state_changed", frame.eventName)
        assertNull(frame.result)
    }

    @Test
    fun `surfaces a failure as an error with its id and text`() {
        val frame = HeosFrameParser.parse(
            """{"heos":{"command":"browse/browse","result":"fail","message":"eid=8&text=Invalid ID&sid=1024"}}""",
        )
        val error = assertNotNull(frame?.asError())
        assertEquals(8, error.errorId)
        assertEquals("Invalid ID", error.text)
        assertTrue(error.isMissingEntity, "eid 8 is the signal we use to prune a stale browse stack")
    }

    @Test
    fun `reads back the sequence used for correlation`() {
        val frame = HeosFrameParser.parse(
            """{"heos":{"command":"system/heart_beat","result":"success","message":"SEQUENCE=42"}}""",
        )
        assertEquals(42L, frame?.sequence)
    }

    @Test
    fun `recognises the interim ack a slow browse sends before its real result`() {
        val ack = HeosFrameParser.parse(
            """{"heos":{"command":"browse/browse","result":"success","message":"command under process&sid=-66917606&SEQUENCE=5"}}""",
        )
        assertTrue(assertNotNull(ack).isCommandUnderProcess)
        assertEquals(5L, ack.sequence, "the ack echoes the same correlation id as the real result")

        val result = HeosFrameParser.parse(
            """{"heos":{"command":"browse/browse","result":"success","message":"sid=-66917606&SEQUENCE=5&returned=1&count=1"},"payload":[]}""",
        )
        assertFalse(assertNotNull(result).isCommandUnderProcess)
    }

    @Test
    fun `ignores noise that is not a frame`() {
        assertNull(HeosFrameParser.parse(""))
        assertNull(HeosFrameParser.parse("   "))
        assertNull(HeosFrameParser.parse("not json"))
        assertNull(HeosFrameParser.parse("""{"something":"else"}"""))
    }
}
