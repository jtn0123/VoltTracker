package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import org.json.JSONException
import org.json.JSONObject

class VoltTrackerDb : SQLiteOpenHelper {
    constructor(context: Context) : this(context, DATABASE_NAME)

    constructor(context: Context, databaseName: String) : super(
        context.applicationContext,
        databaseName,
        null,
        DATABASE_VERSION,
    ) {
        // Write-ahead logging lets dashboard reads (storage summary, trips, route render)
        // run concurrently with the single-thread telemetry writer instead of serializing
        // on a shared lock — the dominant in-drive contention, since the app inserts ~1
        // telemetry row/850 ms while servicing reads. Must be set before the DB opens.
        // The store already issues wal_checkpoint(TRUNCATE) on maintenance/backup paths.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        VoltTrackerSchema.createBaseTables(db)
        VoltTrackerSchema.createObservationTables(db)
        VoltTrackerSchema.createObservationIndexes(db)
        VoltTrackerSchema.createRoadmapTables(db)
        VoltTrackerSchema.createRoadmapIndexes(db)
        VoltTrackerSchema.createPruneIndexes(db)
        VoltTrackerSchema.createSessionTripRollups(db)
        VoltTrackerSchema.createTripListCache(db)
        VoltTrackerSchema.createChargeSessionRollups(db)
        VoltTrackerSchema.createMaintenanceLog(db)
        VoltTrackerSchema.createCellSnapshotIndexes(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) {
            runMigrationStep(db, oldVersion, 2, "telemetry-gps-columns") { target ->
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN latitude REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN longitude REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN accuracy_m REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN gps_speed_mps REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN bearing_deg REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN location_age_ms INTEGER")
            }
        }
        if (oldVersion < 3) {
            runMigrationStep(db, oldVersion, 3, "observation-tables-and-indexes") { target ->
                VoltTrackerSchema.createObservationTables(target)
                VoltTrackerSchema.createObservationIndexes(target)
            }
        }
        if (oldVersion < 4) {
            runMigrationStep(db, oldVersion, 4, "roadmap-tables-and-indexes") { target ->
                VoltTrackerSchema.createRoadmapTables(target)
                VoltTrackerSchema.createRoadmapIndexes(target)
            }
        }
        if (oldVersion < 5) {
            runMigrationStep(db, oldVersion, 5, "charge-transition-and-foreground-columns-with-backfill") { target ->
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN charge_transition_hint INTEGER")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN app_foreground INTEGER")
                backfillTelemetryJsonFlags(target)
            }
        }
        if (oldVersion < 6) {
            runMigrationStep(db, oldVersion, 6, "diagnostic-tables-and-indexes") { target ->
                VoltTrackerSchema.createDiagnosticTables(target)
                VoltTrackerSchema.createDiagnosticIndexes(target)
            }
        }
        if (oldVersion < 7) {
            runMigrationStep(db, oldVersion, 7, "prune-by-time-indexes") { target ->
                VoltTrackerSchema.createPruneIndexes(target)
            }
        }
        if (oldVersion < 8) {
            runMigrationStep(db, oldVersion, 8, "telemetry-hv-pack-columns") { target ->
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN pack_voltage REAL")
                target.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN pack_current_a REAL")
            }
        }
        if (oldVersion < 9) {
            runMigrationStep(db, oldVersion, 9, "session-trip-rollups") { target ->
                VoltTrackerSchema.createSessionTripRollups(target)
            }
        }
        if (oldVersion < 10) {
            runMigrationStep(db, oldVersion, 10, "session-trip-rollup-version") { target ->
                if (!hasColumn(target, TABLE_SESSION_TRIP_ROLLUPS, "rollup_version")) {
                    target.execSQL(
                        "ALTER TABLE $TABLE_SESSION_TRIP_ROLLUPS" +
                            " ADD COLUMN rollup_version INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
        }
        if (oldVersion < 11) {
            runMigrationStep(db, oldVersion, 11, "trip-list-cache") { target ->
                // Table starts empty; ObdStoreTrips backfills it on the next read because the
                // bumped ROLLUP_CACHE_VERSION marks every existing rollup stale.
                VoltTrackerSchema.createTripListCache(target)
            }
        }
        if (oldVersion < 12) {
            runMigrationStep(db, oldVersion, 12, "trip-labels-and-maintenance-log") { target ->
                // M4: nullable label column on materialized trips (non-destructive ADD COLUMN).
                // trip_segments has existed since v4, but guard the ALTER so a partial/legacy
                // schema missing the table can't abort the whole step — the table is recreated by
                // its own (idempotent) roadmap-table DDL below if absent.
                if (!hasTable(target, TABLE_TRIP_SEGMENTS)) {
                    VoltTrackerSchema.createRoadmapTables(target)
                    VoltTrackerSchema.createRoadmapIndexes(target)
                } else if (!hasColumn(target, TABLE_TRIP_SEGMENTS, "label")) {
                    target.execSQL("ALTER TABLE $TABLE_TRIP_SEGMENTS ADD COLUMN label TEXT")
                }
                // M5: user-authored maintenance log (CREATE TABLE IF NOT EXISTS — no data touched).
                VoltTrackerSchema.createMaintenanceLog(target)
            }
        }
        if (oldVersion < 13) {
            runMigrationStep(db, oldVersion, 13, "maintenance-interval-columns") { target ->
                // M1/C4: optional service-interval columns on maintenance_log (non-destructive ADD
                // COLUMN). Existing rows survive with the new columns NULL. The table has existed
                // since v12, but guard the ALTERs so a partial schema missing the table doesn't
                // abort the step — recreate it (idempotently) if absent, then both columns ship.
                if (!hasTable(target, TABLE_MAINTENANCE_LOG)) {
                    VoltTrackerSchema.createMaintenanceLog(target)
                } else {
                    if (!hasColumn(target, TABLE_MAINTENANCE_LOG, "interval_km")) {
                        target.execSQL("ALTER TABLE $TABLE_MAINTENANCE_LOG ADD COLUMN interval_km REAL")
                    }
                    if (!hasColumn(target, TABLE_MAINTENANCE_LOG, "interval_months")) {
                        target.execSQL("ALTER TABLE $TABLE_MAINTENANCE_LOG ADD COLUMN interval_months INTEGER")
                    }
                }
            }
        }
        if (oldVersion < 14) {
            runMigrationStep(db, oldVersion, 14, "charge-session-rollups") { target ->
                // G2: per-session cache for the whole-history inferred-charge scan. Table starts
                // empty (CREATE TABLE IF NOT EXISTS — no data touched); ObdStoreReports backfills it
                // lazily on the next storage-summary read, one finalized session at a time.
                VoltTrackerSchema.createChargeSessionRollups(target)
            }
        }
        if (oldVersion < 15) {
            runMigrationStep(db, oldVersion, 15, "cell-snapshot-index") { target ->
                // Index only (CREATE INDEX IF NOT EXISTS — no data touched): the latest-cell-map
                // projection reads cell_snapshots by parent snapshot on every storage read.
                // cell_snapshots has existed since v4, but guard against a partial/legacy schema
                // missing the table (same rationale as the v12 step) — recreate it idempotently
                // so the CREATE INDEX can't abort the migration.
                if (!hasTable(target, TABLE_CELL_SNAPSHOTS)) {
                    VoltTrackerSchema.createRoadmapTables(target)
                }
                VoltTrackerSchema.createCellSnapshotIndexes(target)
            }
        }
        if (oldVersion < 16) {
            runMigrationStep(db, oldVersion, 16, "vehicle-key-aliases-column") { target ->
                // ADR 0009 (B8): nullable JSON-array column recording the vehicle's key under
                // every identity secret known when its VIN was last read, so DatabaseMerger can
                // recognize the same car across installs keyed by different HMAC secrets.
                // Non-destructive ADD COLUMN; existing rows keep NULL (strict-key merge fallback).
                // vehicles has existed since v4, but guard the ALTER so a partial/legacy schema
                // missing the table can't abort the step (same rationale as the v12 step).
                if (!hasTable(target, TABLE_VEHICLES)) {
                    VoltTrackerSchema.createRoadmapTables(target)
                    VoltTrackerSchema.createRoadmapIndexes(target)
                } else if (!hasColumn(target, TABLE_VEHICLES, "vehicle_key_aliases")) {
                    target.execSQL("ALTER TABLE $TABLE_VEHICLES ADD COLUMN vehicle_key_aliases TEXT")
                }
            }
        }
    }

    fun interface MigrationStep {
        fun apply(db: SQLiteDatabase)
    }

    companion object {
        const val DATABASE_NAME = "volttracker_obd_poc.db"
        const val DATABASE_VERSION = 16

        const val TABLE_SESSIONS = "obd_sessions"
        const val TABLE_TELEMETRY = "telemetry_samples"
        const val TABLE_EVENTS = "status_events"
        const val TABLE_ADAPTER_HISTORY = "adapter_history"
        const val TABLE_PID_OBSERVATIONS = "pid_observations"
        const val TABLE_DIAGNOSTIC_CODES = "diagnostic_codes"
        const val TABLE_LOCATION_SAMPLES = "location_samples"
        const val TABLE_VEHICLES = "vehicles"
        const val TABLE_FIELD_CAPABILITIES = "field_capabilities"
        const val TABLE_TRIP_SEGMENTS = "trip_segments"
        const val TABLE_SESSION_TRIP_ROLLUPS = "session_trip_rollups"
        const val TABLE_TRIP_LIST_CACHE = "trip_list_cache"
        const val TABLE_CHARGE_SESSION_ROLLUPS = "charge_session_rollups"
        const val TABLE_CHARGE_SESSIONS = "charge_sessions"
        const val TABLE_BATTERY_SNAPSHOTS = "battery_snapshots"
        const val TABLE_CELL_SNAPSHOTS = "cell_snapshots"
        const val TABLE_EXPORTS = "exports"
        const val TABLE_MAINTENANCE_LOG = "maintenance_log"

        @JvmField
        val KNOWN_TABLES: Set<String> =
            setOf(
                TABLE_SESSIONS,
                TABLE_TELEMETRY,
                TABLE_EVENTS,
                TABLE_ADAPTER_HISTORY,
                TABLE_PID_OBSERVATIONS,
                TABLE_DIAGNOSTIC_CODES,
                TABLE_LOCATION_SAMPLES,
                TABLE_VEHICLES,
                TABLE_FIELD_CAPABILITIES,
                TABLE_TRIP_SEGMENTS,
                TABLE_SESSION_TRIP_ROLLUPS,
                TABLE_TRIP_LIST_CACHE,
                TABLE_CHARGE_SESSION_ROLLUPS,
                TABLE_CHARGE_SESSIONS,
                TABLE_BATTERY_SNAPSHOTS,
                TABLE_CELL_SNAPSHOTS,
                TABLE_EXPORTS,
                TABLE_MAINTENANCE_LOG,
            )

        /**
         * v5 backfill: derives charge_transition_hint / app_foreground from each row's stored
         * JSON snapshot with a real parse. (The original backfill used
         * `LIKE '%"chargeTransitionHint":true%'`, which missed re-serialized spacing variants and
         * could false-positive on the literal appearing inside a string value.) Runs inside the
         * migration step's transaction; unparseable JSON falls back to the same defaults the LIKE
         * version applied (hint 0, foreground 1).
         */
        private fun backfillTelemetryJsonFlags(db: SQLiteDatabase) {
            val update =
                db.compileStatement(
                    "UPDATE $TABLE_TELEMETRY SET charge_transition_hint = ?, app_foreground = ?" +
                        " WHERE _id = ?",
                )
            update.use { statement ->
                db
                    .rawQuery(
                        "SELECT _id, json FROM $TABLE_TELEMETRY" +
                            " WHERE charge_transition_hint IS NULL OR app_foreground IS NULL",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            var chargeHint = false
                            var foreground = true
                            try {
                                val sample = JSONObject(cursor.getString(1) ?: "")
                                chargeHint = sample.optBoolean("chargeTransitionHint", false)
                                foreground = sample.optBoolean("appForeground", true)
                            } catch (ignored: JSONException) {
                                // Keep the defaults for rows whose snapshot is not valid JSON.
                            }
                            statement.clearBindings()
                            statement.bindLong(1, if (chargeHint) 1L else 0L)
                            statement.bindLong(2, if (foreground) 1L else 0L)
                            statement.bindLong(3, cursor.getLong(0))
                            statement.executeUpdateDelete()
                        }
                    }
            }
        }

        private fun hasTable(
            db: SQLiteDatabase,
            table: String,
        ): Boolean {
            db
                .rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                    arrayOf(table),
                ).use { cursor ->
                    return cursor.moveToFirst()
                }
        }

        private fun hasColumn(
            db: SQLiteDatabase,
            table: String,
            column: String,
        ): Boolean {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (column == cursor.getString(1)) {
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        fun runMigrationStep(
            db: SQLiteDatabase,
            oldVersion: Int,
            targetVersion: Int,
            label: String,
            step: MigrationStep,
        ) {
            Log.i(
                "VoltTrackerDb",
                "migrating v$oldVersion->v$targetVersion ($label) starting",
            )
            try {
                db.transaction {
                    step.apply(db)
                }
                Log.i(
                    "VoltTrackerDb",
                    "migrating v$oldVersion->v$targetVersion ($label) committed",
                )
            } catch (ex: RuntimeException) {
                Log.e(
                    "VoltTrackerDb",
                    "migrating v$oldVersion->v$targetVersion ($label) FAILED - rolling back",
                    ex,
                )
                throw ex
            }
        }
    }
}
