package io.github.ntufar.stasi.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import io.github.ntufar.stasi.MainActivity
import io.github.ntufar.stasi.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "arrival_alerts"
        private const val NOTIFICATION_ID_BASE = 1000
        private const val ROUTE_RELATIVE_SIZE = 1.16f
        private const val MINUTES_RELATIVE_SIZE = 1.22f
    }

    fun createChannel() {
        val name = context.getString(R.string.notification_channel_name)
        val descr = context.getString(R.string.notification_channel_description)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = descr
            enableVibration(true)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    enum class ArrivalPhase {
        Countdown,
        Arrived,
        Departed,
    }

    /**
     * Shows or updates the same notification id so the arrival alert evolves (countdown → arrived → left).
     * [setOnlyAlertOnce] avoids sound/vibration on every poll update after the first post.
     */
    fun showOrUpdateArrivalNotification(
        stopCode: String,
        routeCode: String,
        vehCode: String,
        lineLabel: String,
        stopTitle: String,
        phase: ArrivalPhase,
        minutes: Int = 0,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to_stop", stopCode)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            stopCode.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titlePlain = when (phase) {
            ArrivalPhase.Countdown -> context.getString(R.string.notification_arrival_title, lineLabel)
            ArrivalPhase.Arrived -> context.getString(R.string.notification_arrival_title_arrived, lineLabel)
            ArrivalPhase.Departed -> context.getString(R.string.notification_arrival_title_departed, lineLabel)
        }
        val title = styleRouteInTitle(titlePlain, lineLabel)

        val textPlain = when (phase) {
            ArrivalPhase.Countdown -> when {
                minutes <= 0 -> context.getString(R.string.notification_arrival_arrived, stopTitle)
                else -> context.getString(R.string.notification_arrival_text, minutes, stopTitle)
            }
            ArrivalPhase.Arrived -> context.getString(R.string.notification_arrival_arrived, stopTitle)
            ArrivalPhase.Departed -> context.getString(R.string.notification_arrival_left, stopTitle)
        }
        val text = when (phase) {
            ArrivalPhase.Countdown -> when {
                minutes <= 0 -> textPlain
                else -> {
                    val minutesToken = context.getString(R.string.notification_arrival_minutes_token, minutes)
                    styleMinutesInCountdown(textPlain, minutes, minutesToken)
                }
            }
            else -> textPlain
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .apply {
                if (phase == ArrivalPhase.Countdown && minutes > 0 && text is Spanned) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
            }
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NOTIFICATION_ID_BASE + (stopCode + routeCode + vehCode).hashCode()
        nm.notify(id, notification)
    }

    /** Public line number: segment before " · ", else leading digits (+ optional letter suffix). */
    private fun routeNumberSegment(lineLabel: String): String {
        val t = lineLabel.trim()
        val sep = " · "
        val dot = t.indexOf(sep)
        if (dot > 0) return t.substring(0, dot).trim()
        val m = Regex("""^(\d+[A-Za-zΑ-Ωα-ω]?)""").find(t)
        if (m != null) return m.value.trim()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.isNotEmpty()) return digits
        return t.substringBefore(' ').trim()
    }

    private fun styleRouteInTitle(fullTitle: String, lineLabel: String): CharSequence {
        val routeSeg = routeNumberSegment(lineLabel)
        if (routeSeg.isEmpty()) return fullTitle
        val routeStart = when {
            fullTitle.startsWith(lineLabel) -> lineLabel.indexOf(routeSeg).coerceAtLeast(0)
            else -> fullTitle.indexOf(routeSeg)
        }
        if (routeStart < 0 || routeStart + routeSeg.length > fullTitle.length) return fullTitle
        val ss = SpannableString(fullTitle)
        applyEmphasisSpans(ss, routeStart, routeStart + routeSeg.length, ROUTE_RELATIVE_SIZE)
        return ss
    }

    private fun minutesHighlightRange(plain: String, minutes: Int, minutesToken: String): IntRange? {
        val t = minutesToken.trim()
        if (t.isNotEmpty()) {
            val i = plain.indexOf(t)
            if (i >= 0) return i until i + t.length
        }
        val n = minutes.toString()
        val idx = plain.indexOf(n)
        if (idx < 0) return null
        val after = idx + n.length
        if (after < plain.length) {
            val ch = plain[after]
            if (ch == '\'' || ch == '′' || ch == '΄' || ch == '\u0374' || ch == '\u02B9') {
                return idx until after + 1
            }
        }
        return idx until after
    }

    private fun styleMinutesInCountdown(plain: String, minutes: Int, minutesToken: String): CharSequence {
        if (minutes < 0) return plain
        val range = minutesHighlightRange(plain, minutes, minutesToken) ?: return plain
        val ss = SpannableString(plain)
        applyEmphasisSpans(ss, range.first, range.last + 1, MINUTES_RELATIVE_SIZE)
        return ss
    }

    private fun applyEmphasisSpans(ss: SpannableString, start: Int, end: Int, relativeSize: Float) {
        if (start < 0 || end <= start || end > ss.length) return
        ss.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.setSpan(RelativeSizeSpan(relativeSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
