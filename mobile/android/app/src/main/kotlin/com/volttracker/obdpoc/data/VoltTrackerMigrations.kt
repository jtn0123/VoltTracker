package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_CELL_SNAPSHOTS
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_MAINTENANCE_LOG
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_SESSIONS
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_SESSION_TRIP_ROLLUPS
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_TELEMETRY
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_TRIP_SEGMENTS
import com.volttracker.obdpoc.data.VoltTrackerDb.Companion.TABLE_VEHICLES
import org.json.JSONException
import org.json.JSONObject

/**
 * The schema migration chain, oldest first. [VoltTrackerDb.onUpgrade] runs every step whose
 * [Step.version] is above the installed version, each in its own transaction. Every step must stay
 * re-runnable (IF NOT EXISTS / guarded ADD COLUMN): a downgrade keeps the newer file, and the
 * newer build re-runs its steps when it is installed again.
 */
internal object VoltTrackerMigrations {
    class Step(
        val version: Int,
        val label: String,
        val body: VoltTrackerDb.MigrationStep,
    )

    // v5 backfill batch: bounds migration memory on large telemetry histories (B10).
    private const val BACKFILL_BATCH_SIZE = 500

    val STEPS: List<Step> =
        listOf(
            Step(2, "telemetry-gps-columns") { db ->
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN accuracy_m REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN gps_speed_mps REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN bearing_deg REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN location_age_ms INTEGER")
            },
            Step(3, "observation-tables-and-indexes") { db ->
                VoltTrackerSchema.createObservationTables(db)
                VoltTrackerSchema.createObservationIndexes(db)
            },
            Step(4, "roadmap-tables-and-indexes") { db ->
                VoltTrackerSchema.createRoadmapTables(db)
                VoltTrackerSchema.createRoadmapIndexes(db)
            },
            Step(5, "charge-transition-and-foreground-columns-with-backfill") { db ->
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN charge_transition_hint INTEGER")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN app_foreground INTEGER")
                backfillTelemetryJsonFlags(db)
            },
            Step(6, "diagnostic-tables-and-indexes") { db ->
                VoltTrackerSchema.createDiagnosticTables(db)
                VoltTrackerSchema.createDiagnosticIndexes(db)
            },
            Step(7, "prune-by-time-indexes") { db ->
                VoltTrackerSchema.createPruneIndexes(db)
            },
            Step(8, "telemetry-hv-pack-columns") { db ->
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN pack_voltage REAL")
                db.execSQL("ALTER TABLE $TABLE_TELEMETRY ADD COLUMN pack_current_a REAL")
            },
            Step(9, "session-trip-rollups") { db ->
                VoltTrackerSchema.createSessionTripRollups(db)
            },
            Step(10, "session-trip-rollup-version") { db ->
                if (!hasColumn(db, TABLE_SESSION_TRIP_ROLLUPS, "rollup_version")) {
                    db.execSQL(
                        "ALTER TABLE $TABLE_SESSION_TRIP_ROLLUPS" +
                            " ADD COLUMN rollup_version INTEGER NOT NULL DEFAULT 0",
                    )
                }
            },
            Step(11, "trip-list-cache") { db ->
                // Table starts empty; ObdStoreTrips backfills it on the next read because the
                // bumped ROLLUP_CACHE_VERSION marks every existing rollup stale.
                VoltTrackerSchema.createTripListCache(db)
            },
            Step(12, "trip-labels-and-maintenance-log") { db ->
                // M4: nullable label column on materialized trips (non-destructive ADD COLUMN).
                // trip_segments has existed since v4, but guard the ALTER so a partial/legacy
                // schema missing the table can't abort the whole step — the table is recreated by
                // its own (idempotent) roadmap-table DDL below if absent.
                if (!hasTable(db, TABLE_TRIP_SEGMENTS)) {
                    VoltTrackerSchema.createRoadmapTables(db)
                    VoltTrackerSchema.createRoadmapIndexes(db)
                } else if (!hasColumn(db, TABLE_TRIP_SEGMENTS, "label")) {
                    db.execSQL("ALTER TABLE $TABLE_TRIP_SEGMENTS ADD COLUMN label TEXT")
                }
                // M5: user-authored maintenance log (CREATE TABLE IF NOT EXISTS — no data touched).
                VoltTrackerSchema.createMaintenanceLog(db)
            },
            Step(13, "maintenance-interval-columns") { db ->
                // M1/C4: optional service-interval columns on maintenance_log (non-destructive ADD
                // COLUMN). Existing rows survive with the new columns NULL. The table has existed
                // since v12, but guard the ALTERs so a partial schema missing the table doesn't
                // abort the step — recreate it (idempotently) if absent, then both columns ship.
                if (!hasTable(db, TABLE_MAINTENANCE_LOG)) {
                    VoltTrackerSchema.createMaintenanceLog(db)
                } else {
                    if (!hasColumn(db, TABLE_MAINTENANCE_LOG, "interval_km")) {
                        db.execSQL("ALTER TABLE $TABLE_MAINTENANCE_LOG ADD COLUMN interval_km REAL")
                    }
                    if (!hasColumn(db, TABLE_MAINTENANCE_LOG, "interval_months")) {
                        db.execSQL("ALTER TABLE $TABLE_MAINTENANCE_LOG ADD COLUMN interval_months INTEGER")
                    }
                }
            },
            Step(14, "charge-session-rollups") { db ->
                // G2: per-session cache for the whole-history inferred-charge scan. Table starts
                // empty (CREATE TABLE IF NOT EXISTS — no data touched); ObdStoreReports backfills it
                // lazily on the next storage-summary read, one finalized session at a time.
                VoltTrackerSchema.createChargeSessionRollups(db)
            },
            Step(15, "cell-snapshot-index") { db ->
                // Index only (CREATE INDEX IF NOT EXISTS — no data touched): the latest-cell-map
                // projection reads cell_snapshots by parent snapshot on every storage read.
                // cell_snapshots has existed since v4, but guard against a partial/legacy schema
                // missing the table (same rationale as the v12 step) — recreate it idempotently
                // so the CREATE INDEX can't abort the migration.
                if (!hasTable(db, TABLE_CELL_SNAPSHOTS)) {
                    VoltTrackerSchema.createRoadmapTables(db)
                }
                VoltTrackerSchema.createCellSnapshotIndexes(db)
            },
            Step(16, "vehicle-key-aliases-column") { db ->
                // ADR 0009 (B8): nullable JSON-array column recording the vehicle's key under
                // every identity secret known when its VIN was last read, so DatabaseMerger can
                // recognize the same car across installs keyed by different HMAC secrets.
                // Non-destructive ADD COLUMN; existing rows keep NULL (strict-key merge fallback).
                // vehicles has existed since v4, but guard the ALTER so a partial/legacy schema
                // missing the table can't abort the step (same rationale as the v12 step).
                if (!hasTable(db, TABLE_VEHICLES)) {
                    VoltTrackerSchema.createRoadmapTables(db)
                    VoltTrackerSchema.createRoadmapIndexes(db)
                } else if (!hasColumn(db, TABLE_VEHICLES, "vehicle_key_aliases")) {
                    db.execSQL("ALTER TABLE $TABLE_VEHICLES ADD COLUMN vehicle_key_aliases TEXT")
                }
            },
            Step(17, "gear-aware-trip-split-columns") { db ->
                addGearAwareTripColumns(db)
            },
        )

    /**
     * v17 (gear-aware trip splitting, see TripSplitRules): the PRNDL code and door-open flag per
     * telemetry row, plus the rules version a session was recorded under. Existing sessions get
     * version 0 (legacy) and existing rows NULL gear/door — deliberately NOT backfilled from the
     * row JSON, so trips saved before this version keep their exact windows and route keys.
     * Guarded ADD COLUMNs: a table that already has the column (base tables built from the
     * current DDL) or is missing entirely (a partial/legacy schema, same rationale as the v12
     * step) must not abort the step.
     */
    private fun addGearAwareTripColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, TABLE_SESSIONS, "trip_rules_version", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, TABLE_TELEMETRY, "prndl_raw", "INTEGER")
        addColumnIfMissing(db, TABLE_TELEMETRY, "door_open", "INTEGER")
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        type: String,
    ) {
        if (hasTable(db, table) && !hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    /**
     * v5 backfill: derives charge_transition_hint / app_foreground from each row's stored
     * JSON snapshot with a real parse. (The original backfill used
     * `LIKE '%"chargeTransitionHint":true%'`, which missed re-serialized spacing variants and
     * could false-positive on the literal appearing inside a string value.) Runs inside the
     * migration step's transaction; unparseable JSON falls back to the same defaults the LIKE
     * version applied (hint 0, foreground 1).
     */
    private fun backfillTelemetryJsonFlags(db: SQLiteDatabase) {
        // B10: collect a bounded batch of ids + derived flags, close the cursor, THEN update,
        // and repeat. The UPDATEs remove rows from the cursor's own `IS NULL` predicate, and
        // SQLite's row visitation while the underlying table changes mid-scan is undefined —
        // rows could be skipped and stay unbackfilled forever. Batching (instead of loading
        // the whole table) keeps migration memory flat even for a multi-year telemetry
        // history; each pass shrinks the predicate's result set, so the loop terminates.
        val update =
            db.compileStatement(
                "UPDATE $TABLE_TELEMETRY SET charge_transition_hint = ?, app_foreground = ?" +
                    " WHERE _id = ?",
            )
        update.use { statement ->
            while (true) {
                // Each entry is [_id, charge_transition_hint, app_foreground].
                val pending = ArrayList<LongArray>(BACKFILL_BATCH_SIZE)
                db
                    .rawQuery(
                        "SELECT _id, json FROM $TABLE_TELEMETRY" +
                            " WHERE charge_transition_hint IS NULL OR app_foreground IS NULL" +
                            " LIMIT $BACKFILL_BATCH_SIZE",
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
                            pending.add(
                                longArrayOf(
                                    cursor.getLong(0),
                                    if (chargeHint) 1L else 0L,
                                    if (foreground) 1L else 0L,
                                ),
                            )
                        }
                    }
                if (pending.isEmpty()) break
                for (row in pending) {
                    statement.clearBindings()
                    statement.bindLong(1, row[1])
                    statement.bindLong(2, row[2])
                    statement.bindLong(3, row[0])
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
}
