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
import java.util.Locale

/**
 * Posts the user-facing event notifications decided by [EventNotificationDecider] (M1): charge
 * complete, new diagnostic codes, and battery threshold alerts.
 *
 * Lives in the service/notification layer (never touches the WebView, per the architecture
 * boundary). It owns its own "alerts" channel, separate from the low-importance ongoing
 * foreground-service channel in [ObdNotifications], so these one-shot alerts can be DEFAULT
 * importance.
 *
 * Runtime permission: on API 33+ a notification is silently dropped (logged, never thrown) when
 * `POST_NOTIFICATIONS` is not granted, so a missing grant degrades gracefully instead of crashing.
 */
open class EventNotifier(
    private val context: Context,
) {
    fun createChannel() {
        ensureChannel(context)
    }

    /** Posts the platform notification for [event]; a no-op (logged) when notifications are blocked. */
    open fun notify(event: EventNotificationDecider.Event) {
        if (!canPost()) {
            Log.i(MainActivity.TAG, "event notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val spec = describe(event) ?: return
        try {
            ensureChannel(context)
            manager.notify(spec.notificationId, build(spec.title, spec.text))
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "event notification post failed", ex)
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun build(
        title: String,
        text: String,
    ): android.app.Notification {
        val open =
            Intent()
                .setClass(context, MainActivity::class.java)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap =
            PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private data class NotificationSpec(
        val notificationId: Int,
        val title: String,
        val text: String,
    )

    private fun describe(event: EventNotificationDecider.Event): NotificationSpec? =
        when (event) {
            is EventNotificationDecider.Event.ChargeComplete ->
                NotificationSpec(
                    NOTIFICATION_ID_CHARGE,
                    context.getString(R.string.notification_charge_complete_title),
                    chargeCompleteText(event.energyKwh),
                )
            is EventNotificationDecider.Event.ChargeInterrupted ->
                NotificationSpec(
                    // Shares the charge notification id so it replaces (not stacks with) a prior
                    // charge alert of either kind.
                    NOTIFICATION_ID_CHARGE,
                    context.getString(R.string.notification_charge_interrupted_title),
                    context.getString(
                        R.string.notification_charge_interrupted_text,
                        format1(event.socPct),
                        format0(event.targetPct),
                    ),
                )
            is EventNotificationDecider.Event.TargetSocReached ->
                NotificationSpec(
                    NOTIFICATION_ID_TARGET_SOC,
                    context.getString(R.string.notification_target_soc_title),
                    context.getString(
                        R.string.notification_target_soc_text,
                        format1(event.socPct),
                        format0(event.targetPct),
                    ),
                )
            is EventNotificationDecider.Event.NewDtc -> describeNewDtc(event)
            is EventNotificationDecider.Event.LowSoc ->
                NotificationSpec(
                    NOTIFICATION_ID_LOW_SOC,
                    context.getString(R.string.notification_low_soc_title),
                    context.getString(
                        R.string.notification_low_soc_text,
                        format1(event.socPct),
                        format0(event.thresholdPct),
                    ),
                )
            is EventNotificationDecider.Event.HighPackTemp ->
                NotificationSpec(
                    NOTIFICATION_ID_HIGH_TEMP,
                    context.getString(R.string.notification_high_temp_title),
                    context.getString(
                        R.string.notification_high_temp_text,
                        format1(event.tempC),
                        format0(event.thresholdC),
                    ),
                )
        }

    private fun chargeCompleteText(energyKwh: Double): String =
        // Gate on the displayed precision: a charge that format1 would round to "0.0" uses the
        // clearer no-energy copy instead of a misleading "Added 0.0 kWh".
        if (energyKwh >= MIN_DISPLAYED_KWH) {
            context.getString(R.string.notification_charge_complete_text, format1(energyKwh))
        } else {
            context.getString(R.string.notification_charge_complete_text_no_energy)
        }

    private fun describeNewDtc(event: EventNotificationDecider.Event.NewDtc): NotificationSpec {
        val codes = event.newCodes
        val text =
            context.resources.getQuantityString(
                R.plurals.notification_new_dtc_text,
                codes.size,
                codes.size,
                codes.joinToString(", "),
            )
        return NotificationSpec(
            NOTIFICATION_ID_NEW_DTC,
            context.getString(R.string.notification_new_dtc_title),
            text,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "volt_event_alerts"

        // Distinct ids per event kind so a fresh alert of one kind replaces the prior one of that
        // kind rather than every alert stacking; all are well clear of ObdNotifications/Troubleshooter.
        const val NOTIFICATION_ID_CHARGE = 4301
        const val NOTIFICATION_ID_NEW_DTC = 4302
        const val NOTIFICATION_ID_LOW_SOC = 4303
        const val NOTIFICATION_ID_HIGH_TEMP = 4304
        const val NOTIFICATION_ID_TARGET_SOC = 4305

        // Below this, format1 rounds the energy to "0.0", so the charge-complete copy switches to
        // the no-energy variant rather than claiming "Added 0.0 kWh".
        private const val MIN_DISPLAYED_KWH = 0.05

        @JvmStatic
        fun ensureChannel(ctx: Context?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx == null) {
                return
            }
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notification_events_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            channel.description = ctx.getString(R.string.notification_events_channel_description)
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // These numbers are shown to the user inside notification copy, so they use the device
        // locale (e.g. "3,2 kWh" in de-DE) rather than a fixed US format. Protocol/hex/SQL
        // formatting elsewhere deliberately stays Locale.US — it must not vary by device locale.
        private fun format0(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

        private fun format1(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    }
}
