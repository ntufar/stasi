package io.github.ntufar.stasi.ui.map

/** Truncate OASA stop descriptions for map name labels. */
internal fun truncateStopMapLabel(raw: String, maxChars: Int = 22): String {
    val t = raw.trim()
    if (t.length <= maxChars) return t
    return t.take(maxChars - 1).trimEnd() + "…"
}
