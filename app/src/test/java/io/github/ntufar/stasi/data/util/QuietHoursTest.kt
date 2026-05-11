package io.github.ntufar.stasi.data.util

import io.github.ntufar.stasi.data.repository.QuietHoursSettings
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    @Test
    fun disabled_neverActive() {
        val s = QuietHoursSettings(enabled = false, startMinutes = 22 * 60, endMinutes = 6 * 60)
        assertFalse(isQuietHoursActive(s, LocalTime.of(23, 0)))
    }

    @Test
    fun sameStartEnd_neverActive() {
        val s = QuietHoursSettings(enabled = true, startMinutes = 10 * 60, endMinutes = 10 * 60)
        assertFalse(isQuietHoursActive(s, LocalTime.of(10, 30)))
    }

    @Test
    fun sameDayWindow_inside() {
        val s = QuietHoursSettings(enabled = true, startMinutes = 22 * 60, endMinutes = 23 * 60)
        assertTrue(isQuietHoursActive(s, LocalTime.of(22, 30)))
        assertFalse(isQuietHoursActive(s, LocalTime.of(21, 59)))
        assertFalse(isQuietHoursActive(s, LocalTime.of(23, 0)))
    }

    @Test
    fun overnightWindow_wrapsMidnight() {
        val s = QuietHoursSettings(enabled = true, startMinutes = 22 * 60, endMinutes = 6 * 60)
        assertTrue(isQuietHoursActive(s, LocalTime.of(23, 0)))
        assertTrue(isQuietHoursActive(s, LocalTime.of(3, 0)))
        assertFalse(isQuietHoursActive(s, LocalTime.of(12, 0)))
    }
}
