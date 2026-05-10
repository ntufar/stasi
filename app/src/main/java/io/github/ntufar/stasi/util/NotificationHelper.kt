package io.github.ntufar.stasi.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.ntufar.stasi.MainActivity
import io.github.ntufar.stasi.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "arrival_alerts"
        private const val NOTIFICATION_ID_BASE = 1000
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

        val title = when (phase) {
            ArrivalPhase.Countdown -> context.getString(R.string.notification_arrival_title, lineLabel)
            ArrivalPhase.Arrived -> context.getString(R.string.notification_arrival_title_arrived, lineLabel)
            ArrivalPhase.Departed -> context.getString(R.string.notification_arrival_title_departed, lineLabel)
        }
        val text = when (phase) {
            ArrivalPhase.Countdown -> when {
                minutes <= 0 -> context.getString(R.string.notification_arrival_arrived, stopTitle)
                else -> context.getString(R.string.notification_arrival_text, minutes, stopTitle)
            }
            ArrivalPhase.Arrived -> context.getString(R.string.notification_arrival_arrived, stopTitle)
            ArrivalPhase.Departed -> context.getString(R.string.notification_arrival_left, stopTitle)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NOTIFICATION_ID_BASE + (stopCode + routeCode + vehCode).hashCode()
        nm.notify(id, notification)
    }
}
