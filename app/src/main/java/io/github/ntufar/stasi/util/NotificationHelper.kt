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

    fun showArrivalNotification(
        stopCode: String,
        routeCode: String,
        lineLabel: String,
        stopTitle: String,
        minutes: Int,
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

        val title = context.getString(R.string.notification_arrival_title, lineLabel)
        val text = if (minutes <= 0) {
            context.getString(R.string.notification_arrival_now, stopTitle)
        } else {
            context.getString(R.string.notification_arrival_text, minutes, stopTitle)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NOTIFICATION_ID_BASE + (stopCode + routeCode).hashCode()
        nm.notify(id, notification)
    }
}
