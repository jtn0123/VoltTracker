package com.volttracker.obdpoc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import com.volttracker.obdpoc.data.VinKeyHasher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Versioned, allowlisted user settings stored inside a Volt Tracker database backup. */
object BackupSettingsManifest {
    fun embed(
        context: Context,
        databaseFile: File,
        dashboardPreferencesJson: String?,
    ) {
        val manifest = capture(context, dashboardPreferencesJson)
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_NAME " +
                    "(schema_version INTEGER NOT NULL, manifest_json TEXT NOT NULL)",
            )
            db.execSQL("DELETE FROM $TABLE_NAME")
            db.execSQL(
                "INSERT INTO $TABLE_NAME(schema_version, manifest_json) VALUES(?, ?)",
                arrayOf<Any>(SCHEMA_VERSION, manifest.toString()),
            )
        }
    }

    fun read(databaseFile: File?): JSONObject? {
        if (databaseFile == null || !databaseFile.isFile) return null
        return try {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db
                    .rawQuery(
                        "SELECT manifest_json FROM $TABLE_NAME ORDER BY rowid DESC LIMIT 1",
                        null,
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) null else JSONObject(cursor.getString(0))
                    }
            }
        } catch (_: RuntimeException) {
            // Older database-only backups do not contain this optional table.
            null
        }
    }

    /** Removes the transport-only manifest before a staged database is merged or installed. */
    fun remove(databaseFile: File?): Boolean {
        if (databaseFile == null || !databaseFile.isFile) return false
        return try {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun applyNative(
        context: Context,
        manifest: JSONObject?,
    ) {
        val native = manifest?.optJSONObject("native") ?: return
        val identityKeys = native.optJSONArray("vehicleIdentityKeys")
        if (identityKeys != null) {
            val encoded = ArrayList<String>()
            for (i in 0 until identityKeys.length()) {
                identityKeys.optString(i, "").takeIf { it.isNotBlank() }?.let(encoded::add)
            }
            VinKeyHasher.importSecrets(context, encoded)
        }
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val events = native.optJSONObject("eventNotifications")
        if (events != null) {
            val eventPrefs = EventNotificationPrefs(prefs)
            eventPrefs.setChargeCompleteEnabled(events.optBoolean("chargeComplete", true))
            eventPrefs.setNewDtcEnabled(events.optBoolean("newDtc", true))
            eventPrefs.setLowSocEnabled(events.optBoolean("lowSoc", false))
            eventPrefs.setLowSocThresholdPct(events.optDouble("lowSocThresholdPct", 20.0))
            eventPrefs.setHighPackTempEnabled(events.optBoolean("highPackTemp", false))
            eventPrefs.setHighPackTempThresholdC(events.optDouble("highPackTempThresholdC", 45.0))
            eventPrefs.setTargetSocPct(events.optDouble("targetSocPct", 100.0))
            eventPrefs.setAutoScanOnConnectEnabled(events.optBoolean("autoScanOnConnect", false))
            eventPrefs.setMaintenanceDueEnabled(events.optBoolean("maintenanceDue", false))
            eventPrefs.setTripSummaryEnabled(events.optBoolean("tripSummary", false))
        }
        prefs.edit {
            putBoolean(
                AutoConnectController.PREF_AUTO_CONNECT_ENABLED,
                native.optBoolean("autoConnect", AutoConnectController.DEFAULT_AUTO_CONNECT_ENABLED),
            )
            putBoolean(
                DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE,
                native.optBoolean("keepScreenAwake", false),
            )
            putBoolean(
                DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY,
                native.optBoolean("tripSummary", false),
            )
        }
    }

    fun dashboardPreferences(manifest: JSONObject?): String = manifest?.optJSONObject("dashboard")?.toString() ?: "{}"

    private fun capture(
        context: Context,
        dashboardPreferencesJson: String?,
    ): JSONObject {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val dashboard =
            try {
                JSONObject(dashboardPreferencesJson?.take(MAX_DASHBOARD_JSON_CHARS) ?: "{}")
            } catch (_: RuntimeException) {
                JSONObject()
            }
        val native =
            JSONObject()
                .put("eventNotifications", JSONObject(EventNotificationPrefs(prefs).stateJson()))
                .put("vehicleIdentityKeys", JSONArray(VinKeyHasher.exportSecrets(context)))
                .put(
                    "autoConnect",
                    prefs.getBoolean(
                        AutoConnectController.PREF_AUTO_CONNECT_ENABLED,
                        AutoConnectController.DEFAULT_AUTO_CONNECT_ENABLED,
                    ),
                ).put(
                    "keepScreenAwake",
                    prefs.getBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, false),
                ).put(
                    "tripSummary",
                    prefs.getBoolean(DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY, false),
                )
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("dashboard", dashboard)
            .put("native", native)
    }

    private const val TABLE_NAME = "volt_backup_settings"
    private const val SCHEMA_VERSION = 2
    private const val MAX_DASHBOARD_JSON_CHARS = 64 * 1024
}
