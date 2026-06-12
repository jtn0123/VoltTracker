package com.volttracker.obdpoc

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Extracted from [MainActivity] so the Activity no longer owns the troubleshooter /
 * notify-when-ready bridge state directly.
 */
class TroubleshooterBridge(
    private val activity: MainActivity,
) {
    private var adapterReadyDeadlineMs = 0L

    @Volatile private var adapterReadyActive = false

    /**
     * Distinguishes a probe-driven "connected" status from a user-initiated session that just
     * happens to be running while the adapter-ready schedule is active.
     */
    @Volatile private var probeInFlight = false

    private var adapterReadyHandler: Handler? = null
    private var testConnectionStopHandler: Handler? = null

    private val adapterReadyTick =
        object : Runnable {
            override fun run() {
                if (!adapterReadyActive || System.currentTimeMillis() >= adapterReadyDeadlineMs) {
                    adapterReadyActive = false
                    return
                }
                // Never probe while a real logging session is running: ObdService.startSession
                // begins with stopCurrentSession (restarting the user's live session) and the
                // probe's 25 s auto-stop would then kill it. Skip this tick and reschedule —
                // the "adapter ready" notification stays driven by probe-initiated statuses
                // only (probeInFlight), so a user session never fakes a probe result.
                if (!activity.isLoggingActive()) {
                    startTestConnection()
                }
                if (adapterReadyHandler != null && adapterReadyActive) {
                    adapterReadyHandler?.postDelayed(this, ADAPTER_READY_INTERVAL_MS)
                }
            }
        }

    /**
     * Drain both internal handlers. Called from [MainActivity.onDestroy].
     */
    fun shutdown() {
        adapterReadyHandler?.removeCallbacksAndMessages(null)
        testConnectionStopHandler?.removeCallbacksAndMessages(null)
    }

    /**
     * Called from [MainActivity.startObdService] so any pending test-connection auto-stop is
     * dropped before a fresh connect kicks off.
     */
    fun clearPendingTestConnectionStop() {
        testConnectionStopHandler?.removeCallbacksAndMessages(null)
    }

    // ===== Connection-recovery bridge helpers =================================

    /**
     * Force-stop a competing app surfaced via the `competingApps` status field.
     */
    fun forceStopPackage(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) {
            return false
        }
        if (!CompetingAppDetector.KNOWN_OBD_PACKAGES.contains(packageName.lowercase(Locale.US))) {
            Log.w(MainActivity.TAG, "forceStopPackage rejected non-OBD package $packageName")
            return false
        }
        try {
            activity.packageManager.getPackageInfo(packageName, 0)
        } catch (notInstalled: PackageManager.NameNotFoundException) {
            return false
        }
        return try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            am.killBackgroundProcesses(packageName)
            Log.i(MainActivity.TAG, "forceStopPackage: requested kill of $packageName")
            true
        } catch (ex: SecurityException) {
            Log.w(MainActivity.TAG, "forceStopPackage denied for $packageName", ex)
            false
        }
    }

    /**
     * Forward a cancel request from the dashboard to the running ObdService so its polling engine
     * can bail out of the retry burst.
     */
    fun cancelRetry() {
        val service = Intent(activity, ObdService::class.java)
        service.action = ObdService.ACTION_CANCEL_RETRY
        try {
            activity.startService(service)
        } catch (ignored: IllegalStateException) {
            // Service may have stopped between the JS click and the dispatch.
        }
    }

    /**
     * Open Android Bluetooth settings so the user can forget the adapter and re-pair.
     */
    fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (ex: Exception) {
            Log.w(MainActivity.TAG, "openBluetoothSettings failed", ex)
            activity.publishStatus(
                "blocked",
                activity.getString(R.string.status_bt_settings_open_failed),
                true,
            )
        }
    }

    // ===== Proactive-tool bridge helpers ======================================

    /**
     * Returns the last `n` session summaries as a JSON array string, newest first.
     */
    fun getRecentSessionsJson(n: Int): String =
        try {
            if (n <= 0) {
                "[]"
            } else {
                val store = SessionSummaryStore.getInstance(activity.filesDir)
                val arr = JSONArray()
                for (summary in store.getRecent(n)) {
                    arr.put(summary.toJson())
                }
                arr.toString()
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "getRecentSessionsJson failed", ex)
            "[]"
        }

    /**
     * Bundle recent diagnostics into a zip and launch the system share sheet.
     */
    fun shareDiagnostics() {
        try {
            val share = DiagnosticsShareIntent.buildIntent(activity)
            if (share == null) {
                activity.publishStatus(
                    "blocked",
                    activity.getString(R.string.status_no_diagnostics_yet),
                    true,
                )
                return
            }
            showDiagnosticsDisclosure(share)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "shareDiagnostics failed", ex)
            activity.publishStatus("blocked", activity.getString(R.string.status_diagnostics_build_failed), true)
        }
    }

    fun diagnosticsDisclosureMessage(): String = activity.getString(R.string.diagnostics_disclosure_message)

    fun showDiagnosticsDisclosure(share: Intent) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.dialog_diagnostics_title)
                .setMessage(diagnosticsDisclosureMessage())
                .setPositiveButton(R.string.dialog_share_anyway) { _, _ ->
                    activity.startActivity(
                        Intent.createChooser(share, activity.getString(R.string.chooser_share_diagnostics)),
                    )
                }.setNegativeButton(R.string.dialog_cancel) { _, _ ->
                    activity.publishStatus("ready", activity.getString(R.string.status_diagnostics_cancelled), false)
                }.setOnCancelListener {
                    activity.publishStatus("ready", activity.getString(R.string.status_diagnostics_cancelled), false)
                }.show()
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "showDiagnosticsDisclosure failed", ex)
            activity.publishStatus("blocked", activity.getString(R.string.status_diagnostics_disclosure_failed), true)
        }
    }

    /**
     * Start a one-shot probe session against the last-known adapter and schedule a stop.
     */
    fun startTestConnection() {
        if (activity.isLoggingActive()) {
            // A probe would restart the live session (ObdService.startSession stops the current
            // one first) and the scheduled auto-stop below would then kill it 25 s later.
            activity.publishStatus(
                "blocked",
                activity.getString(R.string.status_test_connection_logging_active),
                true,
            )
            return
        }
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = device.optString("address", "")
        val name = device.optString("name", "Test connection")
        if (address.isEmpty()) {
            activity.publishStatus(
                "blocked",
                activity.getString(R.string.status_no_remembered_adapter_yet),
                true,
            )
            return
        }
        activity.rememberDevice(address, name)
        probeInFlight = true
        activity.startObdService(ObdService.ACTION_CONNECT, address, name)
        if (testConnectionStopHandler == null) {
            testConnectionStopHandler = Handler(activity.mainLooper)
        }
        testConnectionStopHandler?.removeCallbacksAndMessages(null)
        testConnectionStopHandler?.postDelayed(
            {
                probeInFlight = false
                activity.stopObdService()
            },
            TEST_CONNECTION_DURATION_MS,
        )
    }

    /**
     * Periodic test-connection probe for `mins` minutes.
     */
    fun scheduleAdapterReadyNotify(mins: Int) {
        adapterReadyDeadlineMs = System.currentTimeMillis() + mins * 60_000L
        adapterReadyActive = true
        if (adapterReadyHandler == null) {
            adapterReadyHandler = Handler(activity.mainLooper)
        }
        adapterReadyHandler?.removeCallbacksAndMessages(null)
        adapterReadyHandler?.post(adapterReadyTick)
    }

    /**
     * Cancel a running notify-when-ready schedule.
     */
    fun cancelAdapterReadyNotify() {
        stopAdapterReadySchedule()
        cancelAdapterReadyNotification()
    }

    private fun stopAdapterReadySchedule() {
        adapterReadyActive = false
        adapterReadyDeadlineMs = 0L
        probeInFlight = false
        adapterReadyHandler?.removeCallbacksAndMessages(null)
    }

    private fun cancelAdapterReadyNotification() {
        try {
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(ADAPTER_READY_NOTIFICATION_ID)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "adapter-ready notification cancel failed", ex)
        }
    }

    /**
     * Called from the Activity's status-broadcast receiver.
     */
    fun onAdapterStatusForReadyNotify(status: JSONObject?) {
        if (!adapterReadyActive || !probeInFlight) {
            return
        }
        if (!statusMeansAdapterReadyForNotification(status)) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(MainActivity.TAG, "adapter-ready: POST_NOTIFICATIONS not granted, skipping notification")
            cancelAdapterReadyNotify()
            return
        }
        try {
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                val open =
                    Intent()
                        .setClass(activity, MainActivity::class.java)
                        .setPackage(activity.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val tap =
                    PendingIntent.getActivity(
                        activity,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val notification =
                    NotificationCompat
                        .Builder(activity, ObdNotifications.CHANNEL_ID)
                        .setContentTitle(activity.getString(R.string.notification_adapter_ready_title))
                        .setContentText(activity.getString(R.string.notification_adapter_ready_text))
                        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                nm.notify(ADAPTER_READY_NOTIFICATION_ID, notification)
            }
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "adapter-ready notification failed", ex)
        }
        stopAdapterReadySchedule()
    }

    companion object {
        /**
         * How long a single probe is allowed to run before the bridge auto-stops it.
         */
        private const val TEST_CONNECTION_DURATION_MS = 25_000L

        /** Interval between repeated test-connection probes inside the notify-when-ready window. */
        private const val ADAPTER_READY_INTERVAL_MS = 30_000L

        /** ID for the "adapter is ready" notification posted when a scheduled probe connects. */
        private const val ADAPTER_READY_NOTIFICATION_ID = 4711

        /**
         * "Ready" should mean the vehicle is awake, not merely that the ELM adapter still has power.
         */
        private const val ADAPTER_READY_MIN_VOLTS = 13.0

        @JvmStatic
        fun statusMeansAdapterReadyForNotification(status: JSONObject?): Boolean {
            if (status == null || status.optString("state", "") != "connected") {
                return false
            }
            if (!status.has("lastVoltage")) {
                return false
            }
            return status.optDouble("lastVoltage", 0.0) > ADAPTER_READY_MIN_VOLTS
        }
    }
}
