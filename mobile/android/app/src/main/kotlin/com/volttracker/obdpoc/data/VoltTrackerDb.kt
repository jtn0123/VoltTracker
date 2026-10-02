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
        for (step in VoltTrackerMigrations.STEPS) {
            if (oldVersion < step.version) {
                runMigrationStep(db, oldVersion, step.version, step.label, step.body)
            }
        }
    }

    /**
     * An older build opened a database written by a newer one (sideloading a previous APK). The
     * default throws, which crash-loops the app until its data is cleared. Every migration only
     * adds tables, indexes and nullable/defaulted columns, so this build can keep using the newer
     * file as-is: keep the data and let the helper record [newVersion]. When the newer build is
     * installed again it re-runs its steps above [newVersion], which is why every step must stay
     * re-runnable (see [VoltTrackerMigrations]).
     */
    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        Log.w("VoltTrackerDb", "database downgraded from v$oldVersion to v$newVersion; keeping data")
    }

    fun interface MigrationStep {
        fun apply(db: SQLiteDatabase)
    }

    companion object {
        const val DATABASE_NAME = "volttracker_obd_poc.db"
        const val DATABASE_VERSION = 17

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
