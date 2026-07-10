package com.volttracker.obdpoc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import java.util.Locale

/** Optional, low-noise receipt posted only after a meaningful drive is durably materialized. */
class TripSummaryNotifier(
    private val context: Context,
    private val enabled: () -> Boolean,
) {
    fun notifyMaterializedTrip(
        store: ObdLocalStore,
        sessionId: Long,
    ) {
        if (!enabled() || !canPost()) return
        val trip = newestTripForSession(store, sessionId) ?: return
        val distanceMeters = trip.optDouble("distanceMeters", 0.0)
        if (distanceMeters < MIN_SUMMARY_DISTANCE_METERS) return
        val routeKey = trip.optString("id", "")
        if (routeKey.isBlank()) return
        val distanceMiles = distanceMeters / METERS_PER_MILE
        val energyKwh = trip.optDouble("energyKwh", Double.NaN)
        val efficiency = if (energyKwh.isFinite() && energyKwh > 0.0) distanceMiles / energyKwh else Double.NaN
        val text =
            if (efficiency.isFinite()) {
                context.getString(
                    R.string.notification_trip_summary_text,
                    format1(distanceMiles),
                    format1(efficiency),
                )
            } else {
                context.getString(R.string.notification_trip_summary_text_no_efficiency, format1(distanceMiles))
            }
        val open =
            Intent(context, MainActivity::class.java)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TRIP, routeKey)
                .putExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, true)
        val tap =
            PendingIntent.getActivity(
                context,
                TRIP_REQUEST_CODE,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        ensureChannel()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle(context.getString(R.string.notification_trip_summary_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID_TRIP, notification)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "trip summary notification failed", ex)
        }
    }

    private fun newestTripForSession(
        store: ObdLocalStore,
        sessionId: Long,
    ): JSONObject? {
        val trips = store.getTripsJson(TRIP_LOOKBACK)
        for (index in 0 until trips.length()) {
            val trip = trips.optJSONObject(index) ?: continue
            if (trip.optLong("sessionId", -1L) == sessionId) return trip
        }
        return null
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_trip_summary_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = context.getString(R.string.notification_trip_summary_channel_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_ID_TRIP = 4306
        const val CHANNEL_ID = "volt_trip_summaries"
        private const val TRIP_REQUEST_CODE = 4306
        private const val TRIP_LOOKBACK = 20
        private const val MIN_SUMMARY_DISTANCE_METERS = 300.0
        private const val METERS_PER_MILE = 1609.344

        private fun format1(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    }
}
