package com.volttracker.obdpoc

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.VoltTrackerDb
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Validates staged SQLite files before they replace the live Volt Tracker database. */
object RestoreValidator {
    private const val CURRENT_RESTORE_SCHEMA_VERSION = VoltTrackerDb.DATABASE_VERSION
    private val REQUIRED_RESTORE_TABLES =
        arrayOf(
            "obd_sessions",
            "telemetry_samples",
            "status_events",
            "adapter_history",
            "pid_observations",
            "diagnostic_codes",
            "location_samples",
            "vehicles",
            "field_capabilities",
            "trip_segments",
            "session_trip_rollups",
            "charge_sessions",
            "battery_snapshots",
            "cell_snapshots",
            "exports",
        )
    private val REQUIRED_RESTORE_COLUMNS =
        arrayOf(
            arrayOf("obd_sessions", "_id", "mode", "started_at_ms", "status", "sample_count"),
            arrayOf(
                "telemetry_samples",
                "_id",
                "session_id",
                "captured_at_ms",
                "pack_voltage",
                "pack_current_a",
                "json",
            ),
            arrayOf("status_events", "_id", "occurred_at_ms", "kind", "payload"),
            arrayOf("adapter_history", "adapter_key", "last_seen_ms", "last_status"),
            arrayOf("pid_observations", "_id", "session_id", "observed_at_ms", "json"),
            arrayOf("diagnostic_codes", "_id", "dtc", "status", "last_seen_ms"),
            arrayOf("location_samples", "_id", "session_id", "captured_at_ms", "latitude", "longitude"),
            arrayOf("vehicles", "_id", "vin_hash", "vin_redacted", "last_seen_ms"),
            arrayOf("field_capabilities", "_id", "command", "first_seen_ms", "last_seen_ms"),
            arrayOf("trip_segments", "_id", "started_at_ms", "created_at_ms"),
            arrayOf(
                "session_trip_rollups",
                "session_id",
                "counted",
                "distance_m",
                "duration_ms",
                "started_at_ms",
                "rollup_version",
            ),
            arrayOf("charge_sessions", "_id", "started_at_ms", "created_at_ms"),
            arrayOf("battery_snapshots", "_id", "captured_at_ms", "created_at_ms"),
            arrayOf("cell_snapshots", "_id", "battery_snapshot_id", "cell_index"),
            arrayOf("exports", "_id", "created_at_ms", "export_type", "status"),
        )

    @JvmStatic
    fun clearRegenerableRollupCache(file: File?) {
        if (file == null) {
            return
        }
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("DELETE FROM session_trip_rollups")
            db.execSQL("DELETE FROM trip_list_cache")
        } catch (ex: RuntimeException) {
            // Best-effort: the cache rebuilds lazily.
        } finally {
            db?.close()
        }
    }

    @JvmStatic
    fun isVoltTrackerBackup(file: File?): Boolean {
        if (file == null) {
            return false
        }
        val header = ByteArray(16)
        try {
            FileInputStream(file).use { input ->
                if (input.read(header) != header.size ||
                    !String(header, StandardCharsets.US_ASCII).startsWith("SQLite format 3")
                ) {
                    return false
                }
            }
        } catch (ex: IOException) {
            return false
        }

        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            if (db.version != CURRENT_RESTORE_SCHEMA_VERSION) {
                return false
            }
            db.rawQuery(requiredTablesSql(), REQUIRED_RESTORE_TABLES).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != REQUIRED_RESTORE_TABLES.size) {
                    return false
                }
            }
            if (!hasRequiredColumns(db)) {
                return false
            }
            if (!integrityCheckOk(db)) {
                return false
            }
            foreignKeyCheckOk(db)
        } catch (ex: RuntimeException) {
            false
        } finally {
            db?.close()
        }
    }

    private fun hasRequiredColumns(db: SQLiteDatabase): Boolean {
        for (tableAndColumns in REQUIRED_RESTORE_COLUMNS) {
            val table = tableAndColumns[0]
            val columns = HashSet<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            }
            for (i in 1 until tableAndColumns.size) {
                if (!columns.contains(tableAndColumns[i])) {
                    return false
                }
            }
        }
        return true
    }

    private fun integrityCheckOk(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            cursor.moveToFirst() && "ok".equals(cursor.getString(0), ignoreCase = true)
        }

    private fun foreignKeyCheckOk(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            !cursor.moveToFirst()
        }

    private fun requiredTablesSql(): String {
        val placeholders = REQUIRED_RESTORE_TABLES.joinToString(", ") { "?" }
        return "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ($placeholders)"
    }
}
