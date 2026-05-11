package io.github.ntufar.stasi.data.util

import io.github.ntufar.stasi.data.repository.RouteDailyTimetable
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Minutes before the last origin window end (same calendar day) for last-bus UI chips/banners.
 * Also allows a grace window after that end (see [isLastBusApproaching]).
 */
const val LAST_BUS_WARNING_THRESHOLD_MINUTES = 60

private const val LAST_BUS_WARNING_GRACE_PAST_END_MINUTES = 120

fun parseScheduleWallClock(token: String): LocalTime? {
    val parts = token.split(':')
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}

fun scheduleRangeStartLocal(range: String): LocalTime? {
    val s = range.trim()
    if (s.isEmpty()) return null
    val startToken = s.split(Regex("""[-–]""")).firstOrNull()?.trim().orEmpty()
    return parseScheduleWallClock(startToken)
}

fun scheduleRangeEndLocal(range: String): LocalTime? {
    val s = range.trim()
    if (s.isEmpty()) return null
    val endToken = s.split(Regex("""[-–]""")).lastOrNull()?.trim().orEmpty()
    return parseScheduleWallClock(endToken)
}

fun lastServiceEndTime(timetable: RouteDailyTimetable): LocalTime? {
    val endTimes = timetable.originDepartures.flatMap { row ->
        val out = ArrayList<LocalTime>(2)
        scheduleRangeEndLocal(row.primaryRange)?.let { out.add(it) }
        row.secondaryRange?.let { scheduleRangeEndLocal(it) }?.let { out.add(it) }
        out
    }
    return endTimes.maxOrNull()
}

fun isLastBusApproaching(timetable: RouteDailyTimetable, now: ZonedDateTime): Boolean {
    val lastEnd = lastServiceEndTime(timetable) ?: return false
    val zone = now.zone
    val today = now.toLocalDate()
    val target = ZonedDateTime.of(today, lastEnd, zone)
    val minutesUntilEnd = ChronoUnit.MINUTES.between(now, target).toInt()
    return minutesUntilEnd in -LAST_BUS_WARNING_GRACE_PAST_END_MINUTES..LAST_BUS_WARNING_THRESHOLD_MINUTES
}
