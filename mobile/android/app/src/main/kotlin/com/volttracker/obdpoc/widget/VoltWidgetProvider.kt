package com.volttracker.obdpoc.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.volttracker.obdpoc.MainActivity
import com.volttracker.obdpoc.R

/**
 * Home-screen widget showing an at-a-glance vehicle snapshot: SOC (big), a charging/connection
 * line, and an "updated Xm ago" freshness line — or an "open the app" prompt when no data exists.
 *
 * Widgets run out-of-process on their own schedule, so this reads a persisted [WidgetSnapshot]
 * (written by the service via [WidgetUpdater]) rather than binding to the live session. The
 * "what to show" decision is delegated to the pure [WidgetStateFormatter]; this class only resolves
 * the result to string resources and binds the [RemoteViews]. Tapping anywhere opens [MainActivity].
 */
class VoltWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = WidgetSnapshotStore(context).read()
        val views = buildViews(context, snapshot)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        /** Distinct request code so the widget's PendingIntent doesn't collide with the notification's. */
        private const val TAP_REQUEST_CODE = 4208

        /** Renders the [snapshot] into a bound [RemoteViews] for the widget layout. */
        @JvmStatic
        fun buildViews(
            context: Context,
            snapshot: WidgetSnapshot,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_volt)
            val display = WidgetStateFormatter.format(snapshot, System.currentTimeMillis())

            val status = statusText(context, display.status)
            val freshness = freshnessText(context, display)
            views.setTextViewText(R.id.widget_soc, display.socText)
            views.setTextViewText(R.id.widget_status, status)
            views.setTextViewText(R.id.widget_updated, freshness)
            views.setContentDescription(
                R.id.widget_root,
                contentDescription(context, snapshot, display, status, freshness),
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            return views
        }

        /**
         * Builds the spoken (TalkBack) content description. Two accessibility guards over naively
         * formatting the raw display fields:
         * - Unknown SOC: the visible "—" placeholder reads poorly aloud, so the unknown state is
         *   described by its status line (already a full phrase, e.g. "Open the app to connect")
         *   instead of "— state of charge".
         * - Empty freshness: the no-data state has no "updated …" line, so it is not appended and the
         *   result carries no dangling trailing clause.
         */
        private fun contentDescription(
            context: Context,
            snapshot: WidgetSnapshot,
            display: WidgetStateFormatter.WidgetDisplay,
            status: String,
            freshness: String,
        ): String {
            val description =
                if (snapshot.hasSoc()) {
                    context.getString(R.string.widget_content_description, display.socText, status, freshness)
                } else {
                    val title = context.getString(R.string.widget_title)
                    if (freshness.isEmpty()) "$title: $status" else "$title: $status. $freshness"
                }
            return description.trim()
        }

        private fun statusText(
            context: Context,
            status: WidgetStateFormatter.Status,
        ): String =
            context.getString(
                when (status) {
                    WidgetStateFormatter.Status.NO_DATA -> R.string.widget_status_no_data
                    WidgetStateFormatter.Status.CHARGING -> R.string.widget_status_charging
                    WidgetStateFormatter.Status.CONNECTED -> R.string.widget_status_connected
                    WidgetStateFormatter.Status.DISCONNECTED -> R.string.widget_status_disconnected
                },
            )

        private fun freshnessText(
            context: Context,
            display: WidgetStateFormatter.WidgetDisplay,
        ): String {
            val base =
                when (display.freshness) {
                    WidgetStateFormatter.Freshness.NONE -> return ""
                    WidgetStateFormatter.Freshness.JUST_NOW -> context.getString(R.string.widget_updated_just_now)
                    WidgetStateFormatter.Freshness.MINUTES ->
                        context.getString(R.string.widget_updated_minutes, display.freshnessValue)
                    WidgetStateFormatter.Freshness.HOURS ->
                        context.getString(R.string.widget_updated_hours, display.freshnessValue)
                    WidgetStateFormatter.Freshness.DAYS ->
                        context.getString(R.string.widget_updated_days, display.freshnessValue)
                }
            return if (display.stale) context.getString(R.string.widget_updated_stale, base) else base
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val open =
                Intent()
                    .setClass(context, MainActivity::class.java)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                TAP_REQUEST_CODE,
                open,
                PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Pushes a fresh redraw to every placed instance. This is the primary in-app redraw path:
         * [WidgetUpdater] calls it on each meaningful state change (and tests call it directly) to
         * bypass the slow system update cadence rather than waiting for the next [onUpdate].
         */
        @JvmStatic
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, VoltWidgetProvider::class.java)) ?: return
            if (ids.isEmpty()) {
                return
            }
            val views = buildViews(app, WidgetSnapshotStore(app).read())
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }
    }
}
