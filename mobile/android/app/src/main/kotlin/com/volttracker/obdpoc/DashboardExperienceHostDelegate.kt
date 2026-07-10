package com.volttracker.obdpoc

import android.app.Activity
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Native-owned daily-use preferences that affect the Android window or background notifications.
 * Display-only choices continue to live in the dashboard preference store.
 */
class DashboardExperienceHostDelegate(
    private val activity: Activity,
    private val prefs: () -> SharedPreferences?,
    private val loggingActive: () -> Boolean,
    private val publishAppState: () -> Unit,
    private val hasNotificationPermission: () -> Boolean = { true },
    private val ensureNotificationPermission: () -> Unit = {},
) : DashboardExperienceCommands {
    private var resumed = false
    private var activeView = VIEW_DRIVE

    override fun getDashboardExperienceStateJson(): String =
        JSONObject()
            .put("keepScreenAwake", keepScreenAwakeEnabled())
            .put("tripSummary", tripSummaryEnabled())
            .put("lastBackupAtMs", prefs()?.getLong(BackupController.PREF_LAST_BACKUP_AT_MS, 0L) ?: 0L)
            .put("lastBackupTrips", prefs()?.getInt(BackupController.PREF_LAST_BACKUP_TRIPS, 0) ?: 0)
            .toString()

    override fun setKeepScreenAwakeEnabled(enabled: Boolean) {
        prefs()?.edit { putBoolean(PREF_KEEP_SCREEN_AWAKE, enabled) }
        updateWindowFlag()
        publishAppState()
    }

    override fun setTripSummaryEnabled(enabled: Boolean) {
        prefs()?.edit { putBoolean(PREF_TRIP_SUMMARY, enabled) }
        if (enabled && !hasNotificationPermission()) ensureNotificationPermission()
        publishAppState()
    }

    override fun setActiveDashboardView(view: String?) {
        activeView = normalizeView(view)
        updateWindowFlag()
    }

    fun activeViewForSave(): String = activeView

    fun restoreActiveView(view: String?) {
        activeView = normalizeView(view)
        updateWindowFlag()
    }

    fun onResume() {
        resumed = true
        updateWindowFlag()
    }

    fun onPause() {
        resumed = false
        updateWindowFlag()
    }

    fun onLoggingStateChanged() {
        updateWindowFlag()
    }

    fun keepScreenAwakeEnabled(): Boolean = prefs()?.getBoolean(PREF_KEEP_SCREEN_AWAKE, false) == true

    fun tripSummaryEnabled(): Boolean = prefs()?.getBoolean(PREF_TRIP_SUMMARY, false) == true

    private fun updateWindowFlag() {
        val keepAwake = resumed && keepScreenAwakeEnabled() && loggingActive() && activeView in KEEP_AWAKE_VIEWS
        if (keepAwake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        const val PREF_KEEP_SCREEN_AWAKE = "dashboard_keep_screen_awake"
        const val PREF_TRIP_SUMMARY = "dashboard_trip_summary"
        private const val VIEW_DRIVE = "drive"
        private val DASHBOARD_VIEWS = setOf(VIEW_DRIVE, "map", "charge", "insights", "diagnostics", "settings")
        private val KEEP_AWAKE_VIEWS = setOf(VIEW_DRIVE, "map")

        fun normalizeView(view: String?): String = view?.trim()?.takeIf(DASHBOARD_VIEWS::contains) ?: VIEW_DRIVE
    }
}
