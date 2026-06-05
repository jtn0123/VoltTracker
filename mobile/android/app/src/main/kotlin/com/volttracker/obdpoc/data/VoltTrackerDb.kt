package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction

class VoltTrackerDb : SQLiteOpenHelper {
    constructor(context: Context) : this(context, DATABASE_NAME)

    constructor(context: Context, databaseName: String) : super(
        context.applicationContext,
        databaseName,
        null,
        DATABASE_VERSION,
    )

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
                target.execSQL(
                    "UPDATE $TABLE_TELEMETRY SET charge_transition_hint = 1" +
                        " WHERE charge_transition_hint IS NULL" +
                        " AND json LIKE '%\"chargeTransitionHint\":true%'",
                )
                target.execSQL(
                    "UPDATE $TABLE_TELEMETRY SET charge_transition_hint = 0" +
                        " WHERE charge_transition_hint IS NULL",
                )
                target.execSQL(
                    "UPDATE $TABLE_TELEMETRY SET app_foreground = 0" +
                        " WHERE app_foreground IS NULL" +
                        " AND json LIKE '%\"appForeground\":false%'",
                )
                target.execSQL(
                    "UPDATE $TABLE_TELEMETRY SET app_foreground = 1" +
                        " WHERE app_foreground IS NULL",
                )
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
    }

    fun interface MigrationStep {
        fun apply(db: SQLiteDatabase)
    }

    companion object {
        const val DATABASE_NAME = "volttracker_obd_poc.db"
        const val DATABASE_VERSION = 10

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
        const val TABLE_CHARGE_SESSIONS = "charge_sessions"
        const val TABLE_BATTERY_SNAPSHOTS = "battery_snapshots"
        const val TABLE_CELL_SNAPSHOTS = "cell_snapshots"
        const val TABLE_EXPORTS = "exports"

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
                TABLE_CHARGE_SESSIONS,
                TABLE_BATTERY_SNAPSHOTS,
                TABLE_CELL_SNAPSHOTS,
                TABLE_EXPORTS,
            )

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
