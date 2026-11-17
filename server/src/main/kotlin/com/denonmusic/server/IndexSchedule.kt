package com.denonmusic.server

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * When to rebuild the index without being asked: a time of day, on some or all days of the week, in
 * the container's own time zone (`TZ`).
 *
 * Deliberately no more than that. A crawl is half an hour of browse traffic at the receiver, so the
 * useful question is "which quiet hour, and how often", and anything finer is better done by
 * whatever already schedules things in the house calling `POST /index/rebuild`.
 */
data class IndexSchedule(val time: LocalTime, val days: Set<DayOfWeek>) {

    /** The first scheduled moment strictly after [now]. */
    fun next(now: ZonedDateTime): ZonedDateTime {
        var candidate = now.with(time)
        while (!candidate.isAfter(now) || candidate.dayOfWeek !in days) {
            candidate = candidate.plusDays(1).with(time)
        }
        return candidate
    }

    companion object {
        /**
         * @param at `HH:MM`, 24-hour. Null or blank means no schedule at all.
         * @param days comma-separated day names or their first three letters (`sun,wed`); null or
         *   blank means every day.
         * @throws IllegalStateException for anything else - a schedule that silently never fires is
         *   worse than a container that refuses to start.
         */
        fun parse(at: String?, days: String?): IndexSchedule? {
            if (at.isNullOrBlank()) return null
            val time = try {
                LocalTime.parse(at.trim())
            } catch (e: DateTimeParseException) {
                throw IllegalStateException("DENON_REINDEX_AT must be HH:MM (24-hour), got '$at'")
            }
            val chosen = if (days.isNullOrBlank()) {
                DayOfWeek.entries.toSet()
            } else {
                days.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { name ->
                    DayOfWeek.entries.firstOrNull { name.length >= 3 && it.name.lowercase().startsWith(name) }
                        ?: throw IllegalStateException("DENON_REINDEX_DAYS has an unknown day '$name' - use names like sun,wed")
                }.toSet()
            }
            return IndexSchedule(time, chosen)
        }
    }
}
