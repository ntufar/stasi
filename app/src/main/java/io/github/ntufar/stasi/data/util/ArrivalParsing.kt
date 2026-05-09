package io.github.ntufar.stasi.data.util

/**
 * Parses OASA [btime2](https://oasa-telematics-api.readthedocs.io/en/latest/getStopArrivals.html) into minutes.
 * Returns a large sentinel when unknown so arrivals sort last.
 */
fun parseArrivalMinutes(raw: String?): Int {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return ARRIVAL_MINUTES_UNKNOWN
    val digits = t.filter { it.isDigit() }
    if (digits.isEmpty()) return ARRIVAL_MINUTES_UNKNOWN
    return digits.toIntOrNull() ?: ARRIVAL_MINUTES_UNKNOWN
}

const val ARRIVAL_MINUTES_UNKNOWN = 999
