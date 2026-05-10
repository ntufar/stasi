package io.github.ntufar.stasi.util

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes

/**
 * Full freshness line: [templateRes] must contain exactly one `%1$s`, filled with a
 * platform relative time span for [lastUpdatedMillis] (wording follows [context] locale).
 */
fun freshnessUpdatedLabel(
    context: Context,
    lastUpdatedMillis: Long?,
    @StringRes templateRes: Int,
): String? {
    val t = lastUpdatedMillis ?: return null
    val relative = DateUtils.getRelativeTimeSpanString(context, t, false)
    return context.getString(templateRes, relative)
}
