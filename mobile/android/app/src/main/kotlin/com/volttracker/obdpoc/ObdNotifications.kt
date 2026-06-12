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
                // Activities launched from a notification PendingIntent start outside any
                // activity task, so NEW_TASK is required; CLEAR_TOP matches the sibling
                // adapter-ready notification in TroubleshooterBridge.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(context.getString(R.string.app_name))
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
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            channel.description = ctx.getString(R.string.notification_channel_description)
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
