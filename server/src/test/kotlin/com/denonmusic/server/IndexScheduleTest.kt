package com.denonmusic.server

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IndexScheduleTest {

    private val zone = ZoneId.of("Australia/Sydney")

    /** 2026-11-17 is a Tuesday. */
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun `no time means no schedule`() {
        assertNull(IndexSchedule.parse(null, null))
        assertNull(IndexSchedule.parse("  ", "sun"))
    }

    @Test
    fun `a daily schedule fires later today, or tomorrow once today's has passed`() {
        val schedule = IndexSchedule.parse("03:30", null)!!

        assertEquals(at(17, 3, 30), schedule.next(at(17, 1)))
        assertEquals(at(18, 3, 30), schedule.next(at(17, 4)))
        assertEquals(at(18, 3, 30), schedule.next(at(17, 3, 30)), "the moment itself is not 'after' itself")
    }

    @Test
    fun `named days are honoured, whole or abbreviated`() {
        val schedule = IndexSchedule.parse("03:30", "Sun, wednesday")!!

        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY), schedule.days)
        assertEquals(at(18, 3, 30), schedule.next(at(17, 12)))
        assertEquals(at(22, 3, 30), schedule.next(at(18, 12)))
    }

    @Test
    fun `a schedule that could never be meant is refused rather than ignored`() {
        assertFailsWith<IllegalStateException> { IndexSchedule.parse("3am", null) }
        assertFailsWith<IllegalStateException> { IndexSchedule.parse("03:30", "sun,someday") }
        assertFailsWith<IllegalStateException> { IndexSchedule.parse("03:30", "s") }
    }
}
