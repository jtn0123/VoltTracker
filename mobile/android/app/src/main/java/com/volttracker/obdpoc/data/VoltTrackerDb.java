package com.volttracker.obdpoc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class VoltTrackerDb extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "volttracker_obd_poc.db";
    static final int DATABASE_VERSION = 9;

    static final String TABLE_SESSIONS = "obd_sessions";
    static final String TABLE_TELEMETRY = "telemetry_samples";
    static final String TABLE_EVENTS = "status_events";
    static final String TABLE_ADAPTER_HISTORY = "adapter_history";
    static final String TABLE_PID_OBSERVATIONS = "pid_observations";
    static final String TABLE_DIAGNOSTIC_CODES = "diagnostic_codes";
    static final String TABLE_LOCATION_SAMPLES = "location_samples";
    static final String TABLE_VEHICLES = "vehicles";
    static final String TABLE_FIELD_CAPABILITIES = "field_capabilities";
    static final String TABLE_TRIP_SEGMENTS = "trip_segments";
    static final String TABLE_SESSION_TRIP_ROLLUPS = "session_trip_rollups";
    static final String TABLE_CHARGE_SESSIONS = "charge_sessions";
    static final String TABLE_BATTERY_SNAPSHOTS = "battery_snapshots";
    static final String TABLE_CELL_SNAPSHOTS = "cell_snapshots";
    static final String TABLE_EXPORTS = "exports";

    /**
     * Allow-list of every table name the app may reference in SQL string-built by helpers in {@link
     * ObdStoreSupport}. If a name passed by a caller is not in this set, the helper throws {@link
     * IllegalArgumentException} rather than inlining the value into SQL.
     */
    static final Set<String> KNOWN_TABLES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
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
                                    TABLE_EXPORTS)));

    VoltTrackerDb(Context context) {
        this(context, DATABASE_NAME);
    }

    /**
     * Test-only constructor that lets a migration test point at an isolated DB file (so the test
     * doesn't trample the default production DB and isn't affected by leftover state from a prior
     * test run).
     */
    VoltTrackerDb(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        VoltTrackerSchema.createBaseTables(db);
        VoltTrackerSchema.createObservationTables(db);
        VoltTrackerSchema.createObservationIndexes(db);
        VoltTrackerSchema.createRoadmapTables(db);
        VoltTrackerSchema.createRoadmapIndexes(db);
        VoltTrackerSchema.createPruneIndexes(db);
        VoltTrackerSchema.createSessionTripRollups(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            runMigrationStep(
                    db,
                    oldVersion,
                    2,
                    "telemetry-gps-columns",
                    target -> {
                        target.execSQL(
                                "ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN latitude REAL");
                        target.execSQL(
                                "ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN longitude REAL");
                        target.execSQL(
                                "ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN accuracy_m REAL");
                        target.execSQL(
                                "ALTER TABLE "
                                        + TABLE_TELEMETRY
                                        + " ADD COLUMN gps_speed_mps REAL");
                        target.execSQL(
                                "ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN bearing_deg REAL");
                        target.execSQL(
                                "ALTER TABLE "
                                        + TABLE_TELEMETRY
                                        + " ADD COLUMN location_age_ms INTEGER");
                    });
        }
        if (oldVersion < 3) {
            runMigrationStep(
                    db,
                    oldVersion,
                    3,
                    "observation-tables-and-indexes",
                    target -> {
                        VoltTrackerSchema.createObservationTables(target);
                        VoltTrackerSchema.createObservationIndexes(target);
                    });
        }
        if (oldVersion < 4) {
            runMigrationStep(
                    db,
                    oldVersion,
                    4,
                    "roadmap-tables-and-indexes",
                    target -> {
                        VoltTrackerSchema.createRoadmapTables(target);
                        VoltTrackerSchema.createRoadmapIndexes(target);
                    });
        }
        if (oldVersion < 5) {
            runMigrationStep(
                    db,
                    oldVersion,
                    5,
                    "charge-transition-and-foreground-columns-with-backfill",
                    target -> {
                        target.execSQL(
                                "ALTER TABLE "
                                        + TABLE_TELEMETRY
                                        + " ADD COLUMN charge_transition_hint INTEGER");
                        target.execSQL(
                                "ALTER TABLE "
                                        + TABLE_TELEMETRY
                                        + " ADD COLUMN app_foreground INTEGER");
                        // Backfill existing rows from their stored JSON so the new columns are
                        // not NULL.
                        target.execSQL(
                                "UPDATE "
                                        + TABLE_TELEMETRY
                                        + " SET charge_transition_hint = 1"
                                        + " WHERE charge_transition_hint IS NULL"
                                        // Match the typed JSON value so a hypothetical
                                        // "chargeTransitionHint":false row cannot accidentally
                                        // be backfilled as 1. Today the engine only ever writes
                                        // true, but the substring-only match was brittle to
                                        // any future change in either direction.
                                        + " AND json LIKE '%\"chargeTransitionHint\":true%'");
                        target.execSQL(
                                "UPDATE "
                                        + TABLE_TELEMETRY
                                        + " SET charge_transition_hint = 0"
                                        + " WHERE charge_transition_hint IS NULL");
                        target.execSQL(
                                "UPDATE "
                                        + TABLE_TELEMETRY
                                        + " SET app_foreground = 0"
                                        + " WHERE app_foreground IS NULL"
                                        + " AND json LIKE '%\"appForeground\":false%'");
                        target.execSQL(
                                "UPDATE "
                                        + TABLE_TELEMETRY
                                        + " SET app_foreground = 1"
                                        + " WHERE app_foreground IS NULL");
                    });
        }
        if (oldVersion < 6) {
            runMigrationStep(
                    db,
                    oldVersion,
                    6,
                    "diagnostic-tables-and-indexes",
                    target -> {
                        VoltTrackerSchema.createDiagnosticTables(target);
                        VoltTrackerSchema.createDiagnosticIndexes(target);
                    });
        }
        if (oldVersion < 7) {
            runMigrationStep(
                    db,
                    oldVersion,
                    7,
                    "prune-by-time-indexes",
                    VoltTrackerSchema::createPruneIndexes);
        }
        if (oldVersion < 8) {
            runMigrationStep(
                    db,
                    oldVersion,
                    8,
                    "telemetry-hv-pack-columns",
                    target -> {
                        // Raw HV pack voltage (V) and current (A, discharge positive) so the
                        // trip materializer can integrate V·I for energy_kwh and the dashboard
                        // can show the real EV pack readings instead of the aux 12V battery.
                        // Older rows leave these NULL; no backfill is possible because the prior
                        // sample JSON only stored the computed power_kw, not its inputs.
                        target.execSQL(
                                "ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN pack_voltage REAL");
                        target.execSQL(
                                "ALTER TABLE "
                                        + TABLE_TELEMETRY
                                        + " ADD COLUMN pack_current_a REAL");
                    });
        }
        if (oldVersion < 9) {
            runMigrationStep(
                    db,
                    oldVersion,
                    9,
                    "session-trip-rollups",
                    VoltTrackerSchema::createSessionTripRollups);
        }
    }

    /**
     * Runs a single migration step inside a database transaction.
     *
     * <p>If {@code step} throws, the transaction is not marked successful and SQLite rolls back
     * every change made by this step — so a half-applied schema cannot stick. The original
     * exception is rethrown after a logged event so Android's {@link SQLiteOpenHelper} treats the
     * upgrade as failed (the next launch retries from the same {@code oldVersion}). Without this
     * wrapper, a failing ALTER mid-step leaves the DB at {@code oldVersion} but with some columns
     * already added — re-running the migration then fails with "duplicate column", silently masking
     * the original failure.
     */
    // Package-private so VoltTrackerDbMigrationTest can verify rollback semantics directly.
    static void runMigrationStep(
            SQLiteDatabase db,
            int oldVersion,
            int targetVersion,
            String label,
            MigrationStep step) {
        Log.i(
                "VoltTrackerDb",
                "migrating v" + oldVersion + "->v" + targetVersion + " (" + label + ") starting");
        db.beginTransaction();
        try {
            step.apply(db);
            db.setTransactionSuccessful();
            Log.i(
                    "VoltTrackerDb",
                    "migrating v"
                            + oldVersion
                            + "->v"
                            + targetVersion
                            + " ("
                            + label
                            + ") committed");
        } catch (RuntimeException ex) {
            Log.e(
                    "VoltTrackerDb",
                    "migrating v"
                            + oldVersion
                            + "->v"
                            + targetVersion
                            + " ("
                            + label
                            + ") FAILED — rolling back",
                    ex);
            throw ex;
        } finally {
            db.endTransaction();
        }
    }

    @FunctionalInterface
    interface MigrationStep {
        void apply(SQLiteDatabase db);
    }
}
