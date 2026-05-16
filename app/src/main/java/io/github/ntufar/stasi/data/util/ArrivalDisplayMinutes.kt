package io.github.ntufar.stasi.data.util

/**
 * Minutes shown between API polls: [minutes] is the value at [snapshotMillis]; subtract whole
 * minutes elapsed since then so the countdown does not look frozen when the API repeats the same
 * integer or refresh is delayed.
 */
fun effectiveMinutesSinceSnapshot(
    minutes: Int,
    snapshotMillis: Long?,
    nowMillis: Long,
): Int {
    if (minutes >= ARRIVAL_MINUTES_UNKNOWN) return minutes
    val base = snapshotMillis ?: return minutes
    val elapsedMin = ((nowMillis - base) / 60_000L).toInt().coerceAtLeast(0)
    return (minutes - elapsedMin).coerceAtLeast(0)
}
