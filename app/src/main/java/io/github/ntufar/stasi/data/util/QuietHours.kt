package io.github.ntufar.stasi.data.util

import io.github.ntufar.stasi.data.repository.QuietHoursSettings
import java.time.LocalTime

/** True when [now] falls inside configured quiet hours (local wall clock). */
fun isQuietHoursActive(settings: QuietHoursSettings, now: LocalTime): Boolean {
    if (!settings.enabled) return false
    val start = settings.startMinutes
    val end = settings.endMinutes
    if (start == end) return false
    val nowMinutes = now.hour * 60 + now.minute
    return if (start < end) {
        nowMinutes in start until end
    } else {
        nowMinutes >= start || nowMinutes < end
    }
}
