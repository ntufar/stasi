package io.github.ntufar.stasi.data.util

import io.github.ntufar.stasi.data.repository.RouteDailyTimetable
import io.github.ntufar.stasi.data.repository.RouteDailyTimetableRow
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyScheduleWallClockTest {

    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    @Test
    fun parseScheduleWallClock_parsesHm() {
        assertEquals(LocalTime.of(4, 15), parseScheduleWallClock("04:15"))
    }

    @Test
    fun parseScheduleWallClock_rejectsInvalid() {
        assertNull(parseScheduleWallClock("24:00"))
        assertNull(parseScheduleWallClock("abc"))
    }

    @Test
    fun scheduleRangeStartEnd_parsesEnDashRange() {
        assertEquals(LocalTime.of(5, 30), scheduleRangeStartLocal("05:30–23:45"))
        assertEquals(LocalTime.of(23, 45), scheduleRangeEndLocal("05:30–23:45"))
    }

    @Test
    fun lastServiceEndTime_maxAcrossOriginRows() {
        val tt = RouteDailyTimetable(
            originDepartures = listOf(
                RouteDailyTimetableRow("05:00–08:00", null),
                RouteDailyTimetableRow("16:00–23:45", null),
            ),
            terminusDepartures = emptyList(),
        )
        assertEquals(LocalTime.of(23, 45), lastServiceEndTime(tt))
    }

    @Test
    fun isLastBusApproaching_trueWhenWithinThresholdBeforeEnd() {
        val tt = RouteDailyTimetable(
            originDepartures = listOf(RouteDailyTimetableRow("05:00–23:00", null)),
            terminusDepartures = emptyList(),
        )
        val now = ZonedDateTime.of(2026, 5, 11, 22, 15, 0, 0, athens)
        assertTrue(isLastBusApproaching(tt, now))
    }

    @Test
    fun isLastBusApproaching_falseWhenFarBeforeEnd() {
        val tt = RouteDailyTimetable(
            originDepartures = listOf(RouteDailyTimetableRow("05:00–23:00", null)),
            terminusDepartures = emptyList(),
        )
        val now = ZonedDateTime.of(2026, 5, 11, 20, 0, 0, 0, athens)
        assertFalse(isLastBusApproaching(tt, now))
    }

    @Test
    fun isLastBusApproaching_trueWithinGraceAfterEnd() {
        val tt = RouteDailyTimetable(
            originDepartures = listOf(RouteDailyTimetableRow("05:00–22:00", null)),
            terminusDepartures = emptyList(),
        )
        val now = ZonedDateTime.of(2026, 5, 11, 23, 0, 0, 0, athens)
        assertTrue(isLastBusApproaching(tt, now))
    }

    @Test
    fun isLastBusApproaching_falseForEmptyTimetable() {
        val tt = RouteDailyTimetable(emptyList(), emptyList())
        val now = ZonedDateTime.now(athens)
        assertFalse(isLastBusApproaching(tt, now))
    }
}
