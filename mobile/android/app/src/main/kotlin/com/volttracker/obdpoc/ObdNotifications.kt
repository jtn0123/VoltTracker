package com.volttracker.obdpoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds and posts the ongoing foreground-service notification for [ObdService].
 */
class ObdNotifications(
    private val context: Context,
) {
    fun createChannel() {
        ensureChannel(context)
    }

    fun build(text: String): Notification {
        val open =
            Intent()
                .setClass(context, MainActivity::class.java)
                .setPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle("Volt Tracker OBD")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Updates the already-showing notification text. */
    fun post(text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, build(text))
    }

    companion object {
        const val NOTIFICATION_ID: Int = 4207
        const val CHANNEL_ID: String = "volt_obd_connection"

        @JvmStatic
        fun ensureChannel(ctx: Context?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx == null) {
                return
            }
            val channel = NotificationChannel(CHANNEL_ID, "OBD connection", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows while Volt Tracker is connected to an OBD adapter."
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
