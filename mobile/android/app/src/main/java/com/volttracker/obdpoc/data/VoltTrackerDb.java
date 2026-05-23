package com.volttracker.obdpoc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class VoltTrackerDb extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "volttracker_obd_poc.db";
    static final int DATABASE_VERSION = 6;

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
                                    TABLE_CHARGE_SESSIONS,
                                    TABLE_BATTERY_SNAPSHOTS,
                                    TABLE_CELL_SNAPSHOTS,
                                    TABLE_EXPORTS)));

    VoltTrackerDb(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE "
                        + TABLE_SESSIONS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "mode TEXT NOT NULL,"
                        + "adapter_address TEXT,"
                        + "adapter_name TEXT,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER,"
                        + "status TEXT NOT NULL,"
                        + "supported_pids TEXT,"
                        + "sample_count INTEGER NOT NULL DEFAULT 0,"
                        + "last_event_at_ms INTEGER,"
                        + "created_at_ms INTEGER NOT NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE "
                        + TABLE_TELEMETRY
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER NOT NULL,"
                        + "captured_at_ms INTEGER NOT NULL,"
                        + "source TEXT,"
                        + "vehicle_state TEXT,"
                        + "speed_kph INTEGER,"
                        + "rpm INTEGER,"
                        + "coolant_c INTEGER,"
                        + "load_pct INTEGER,"
                        + "throttle_pct INTEGER,"
                        + "voltage REAL,"
                        + "soc REAL,"
                        + "battery_temp REAL,"
                        + "power_kw REAL,"
                        + "latitude REAL,"
                        + "longitude REAL,"
                        + "accuracy_m REAL,"
                        + "gps_speed_mps REAL,"
                        + "bearing_deg REAL,"
                        + "location_age_ms INTEGER,"
                        + "sample_number INTEGER,"
                        + "session_ms INTEGER,"
                        + "charge_transition_hint INTEGER,"
                        + "app_foreground INTEGER,"
                        + "raw TEXT,"
                        + "json TEXT NOT NULL,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE CASCADE"
                        + ")");

        db.execSQL(
                "CREATE TABLE "
                        + TABLE_EVENTS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER,"
                        + "occurred_at_ms INTEGER NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "state TEXT,"
                        + "detail TEXT,"
                        + "blocked INTEGER NOT NULL DEFAULT 0,"
                        + "payload TEXT NOT NULL,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE SET NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE "
                        + TABLE_ADAPTER_HISTORY
                        + " ("
                        + "adapter_key TEXT PRIMARY KEY,"
                        + "address TEXT,"
                        + "name TEXT,"
                        + "first_seen_ms INTEGER NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "connect_count INTEGER NOT NULL DEFAULT 0,"
                        + "scan_count INTEGER NOT NULL DEFAULT 0,"
                        + "demo_count INTEGER NOT NULL DEFAULT 0,"
                        + "sample_count INTEGER NOT NULL DEFAULT 0,"
                        + "last_session_id INTEGER,"
                        + "last_mode TEXT,"
                        + "last_status TEXT,"
                        + "supported_pids TEXT,"
                        + "last_event_detail TEXT"
                        + ")");

        db.execSQL(
                "CREATE INDEX idx_sessions_started ON " + TABLE_SESSIONS + "(started_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX idx_telemetry_session_time ON "
                        + TABLE_TELEMETRY
                        + "(session_id, captured_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX idx_events_session_time ON "
                        + TABLE_EVENTS
                        + "(session_id, occurred_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX idx_adapter_history_seen ON "
                        + TABLE_ADAPTER_HISTORY
                        + "(last_seen_ms DESC)");
        createObservationTables(db);
        createObservationIndexes(db);
        createRoadmapTables(db);
        createRoadmapIndexes(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN latitude REAL");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN longitude REAL");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN accuracy_m REAL");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN gps_speed_mps REAL");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN bearing_deg REAL");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN location_age_ms INTEGER");
        }
        if (oldVersion < 3) {
            createObservationTables(db);
            createObservationIndexes(db);
        }
        if (oldVersion < 4) {
            createRoadmapTables(db);
            createRoadmapIndexes(db);
        }
        if (oldVersion < 5) {
            db.execSQL(
                    "ALTER TABLE "
                            + TABLE_TELEMETRY
                            + " ADD COLUMN charge_transition_hint INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_TELEMETRY + " ADD COLUMN app_foreground INTEGER");
            // Backfill existing rows from their stored JSON so the new columns are not NULL.
            db.execSQL(
                    "UPDATE "
                            + TABLE_TELEMETRY
                            + " SET charge_transition_hint = 1"
                            + " WHERE charge_transition_hint IS NULL AND json LIKE '%chargeTransitionHint%'");
            db.execSQL(
                    "UPDATE "
                            + TABLE_TELEMETRY
                            + " SET charge_transition_hint = 0"
                            + " WHERE charge_transition_hint IS NULL");
            db.execSQL(
                    "UPDATE "
                            + TABLE_TELEMETRY
                            + " SET app_foreground = 0"
                            + " WHERE app_foreground IS NULL AND json LIKE '%\"appForeground\":false%'");
            db.execSQL(
                    "UPDATE "
                            + TABLE_TELEMETRY
                            + " SET app_foreground = 1"
                            + " WHERE app_foreground IS NULL");
        }
        if (oldVersion < 6) {
            createDiagnosticTables(db);
            createDiagnosticIndexes(db);
        }
    }

    private static void createObservationTables(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_PID_OBSERVATIONS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER NOT NULL,"
                        + "observed_at_ms INTEGER NOT NULL,"
                        + "command TEXT,"
                        + "header TEXT,"
                        + "pid TEXT,"
                        + "name TEXT,"
                        + "value_text TEXT,"
                        + "value_numeric REAL,"
                        + "unit TEXT,"
                        + "raw_request TEXT,"
                        + "raw_response TEXT,"
                        + "json TEXT NOT NULL,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE CASCADE"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_LOCATION_SAMPLES
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER NOT NULL,"
                        + "captured_at_ms INTEGER NOT NULL,"
                        + "provider TEXT,"
                        + "latitude REAL NOT NULL,"
                        + "longitude REAL NOT NULL,"
                        + "accuracy_m REAL,"
                        + "altitude_m REAL,"
                        + "speed_mps REAL,"
                        + "bearing_deg REAL,"
                        + "location_age_ms INTEGER,"
                        + "elapsed_realtime_nanos INTEGER,"
                        + "json TEXT NOT NULL,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE CASCADE"
                        + ")");
        createDiagnosticTables(db);
    }

    private static void createObservationIndexes(SQLiteDatabase db) {
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_pid_observations_session_time ON "
                        + TABLE_PID_OBSERVATIONS
                        + "(session_id, observed_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_pid_observations_command_header ON "
                        + TABLE_PID_OBSERVATIONS
                        + "(command, header, observed_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_location_samples_session_time ON "
                        + TABLE_LOCATION_SAMPLES
                        + "(session_id, captured_at_ms DESC)");
        createDiagnosticIndexes(db);
    }

    private static void createDiagnosticTables(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_DIAGNOSTIC_CODES
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "dtc TEXT NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "status_label TEXT,"
                        + "module_key TEXT NOT NULL,"
                        + "module_name TEXT,"
                        + "header TEXT,"
                        + "first_seen_ms INTEGER NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "seen_count INTEGER NOT NULL DEFAULT 1,"
                        + "last_session_id INTEGER,"
                        + "raw_response TEXT,"
                        + "json TEXT NOT NULL,"
                        + "UNIQUE(module_key, dtc, status)"
                        + ")");
    }

    private static void createDiagnosticIndexes(SQLiteDatabase db) {
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_diagnostic_codes_last_seen ON "
                        + TABLE_DIAGNOSTIC_CODES
                        + "(last_seen_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_diagnostic_codes_lookup ON "
                        + TABLE_DIAGNOSTIC_CODES
                        + "(module_key, dtc, status)");
    }

    private static void createRoadmapTables(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_VEHICLES
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "vehicle_key TEXT NOT NULL UNIQUE,"
                        + "display_name TEXT,"
                        + "make TEXT,"
                        + "model TEXT,"
                        + "model_year INTEGER,"
                        + "vin_redacted TEXT,"
                        + "vin_hash TEXT,"
                        + "vin_source TEXT,"
                        + "first_seen_ms INTEGER NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "updated_at_ms INTEGER NOT NULL,"
                        + "metadata_json TEXT"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_FIELD_CAPABILITIES
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "vehicle_id INTEGER,"
                        + "adapter_key TEXT,"
                        + "protocol TEXT,"
                        + "header TEXT,"
                        + "command TEXT NOT NULL,"
                        + "pid TEXT,"
                        + "name TEXT,"
                        + "unit TEXT,"
                        + "supported INTEGER NOT NULL DEFAULT 1,"
                        + "response_count INTEGER NOT NULL DEFAULT 0,"
                        + "first_seen_ms INTEGER NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "sample_json TEXT,"
                        + "FOREIGN KEY(vehicle_id) REFERENCES "
                        + TABLE_VEHICLES
                        + "(_id) ON DELETE SET NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_TRIP_SEGMENTS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER,"
                        + "vehicle_id INTEGER,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER,"
                        + "start_sample_id INTEGER,"
                        + "end_sample_id INTEGER,"
                        + "route_available INTEGER NOT NULL DEFAULT 0,"
                        + "distance_m REAL,"
                        + "max_speed_kph REAL,"
                        + "avg_speed_kph REAL,"
                        + "energy_kwh REAL,"
                        + "classification TEXT,"
                        + "confidence REAL,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "summary_json TEXT,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(vehicle_id) REFERENCES "
                        + TABLE_VEHICLES
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(start_sample_id) REFERENCES "
                        + TABLE_TELEMETRY
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(end_sample_id) REFERENCES "
                        + TABLE_TELEMETRY
                        + "(_id) ON DELETE SET NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_CHARGE_SESSIONS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER,"
                        + "vehicle_id INTEGER,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER,"
                        + "charger_type TEXT,"
                        + "start_soc REAL,"
                        + "end_soc REAL,"
                        + "start_sample_id INTEGER,"
                        + "end_sample_id INTEGER,"
                        + "voltage REAL,"
                        + "current_a REAL,"
                        + "power_kw REAL,"
                        + "energy_kwh REAL,"
                        + "interrupted INTEGER NOT NULL DEFAULT 0,"
                        + "confidence REAL,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "summary_json TEXT,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(vehicle_id) REFERENCES "
                        + TABLE_VEHICLES
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(start_sample_id) REFERENCES "
                        + TABLE_TELEMETRY
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(end_sample_id) REFERENCES "
                        + TABLE_TELEMETRY
                        + "(_id) ON DELETE SET NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_BATTERY_SNAPSHOTS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER,"
                        + "vehicle_id INTEGER,"
                        + "captured_at_ms INTEGER NOT NULL,"
                        + "soc REAL,"
                        + "capacity_ah REAL,"
                        + "soh_pct REAL,"
                        + "pack_voltage REAL,"
                        + "pack_current_a REAL,"
                        + "pack_power_kw REAL,"
                        + "battery_temp_c REAL,"
                        + "odometer_km REAL,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "json TEXT,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(vehicle_id) REFERENCES "
                        + TABLE_VEHICLES
                        + "(_id) ON DELETE SET NULL"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_CELL_SNAPSHOTS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "battery_snapshot_id INTEGER NOT NULL,"
                        + "cell_index INTEGER NOT NULL,"
                        + "voltage REAL,"
                        + "temperature_c REAL,"
                        + "resistance_mohm REAL,"
                        + "balance_mv REAL,"
                        + "json TEXT,"
                        + "FOREIGN KEY(battery_snapshot_id) REFERENCES "
                        + TABLE_BATTERY_SNAPSHOTS
                        + "(_id) ON DELETE CASCADE"
                        + ")");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE_EXPORTS
                        + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id INTEGER,"
                        + "vehicle_id INTEGER,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "exported_at_ms INTEGER,"
                        + "range_start_ms INTEGER,"
                        + "range_end_ms INTEGER,"
                        + "export_type TEXT NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "file_name TEXT,"
                        + "mime_type TEXT,"
                        + "bytes INTEGER,"
                        + "destination TEXT,"
                        + "include_precise_location INTEGER NOT NULL DEFAULT 0,"
                        + "include_raw_logs INTEGER NOT NULL DEFAULT 0,"
                        + "error TEXT,"
                        + "manifest_json TEXT,"
                        + "FOREIGN KEY(session_id) REFERENCES "
                        + TABLE_SESSIONS
                        + "(_id) ON DELETE SET NULL,"
                        + "FOREIGN KEY(vehicle_id) REFERENCES "
                        + TABLE_VEHICLES
                        + "(_id) ON DELETE SET NULL"
                        + ")");
    }

    private static void createRoadmapIndexes(SQLiteDatabase db) {
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_vehicles_last_seen ON "
                        + TABLE_VEHICLES
                        + "(last_seen_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_field_capabilities_vehicle_seen ON "
                        + TABLE_FIELD_CAPABILITIES
                        + "(vehicle_id, last_seen_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_field_capabilities_lookup ON "
                        + TABLE_FIELD_CAPABILITIES
                        + "(adapter_key, protocol, header, command, pid)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_trip_segments_session_time ON "
                        + TABLE_TRIP_SEGMENTS
                        + "(session_id, started_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_trip_segments_vehicle_time ON "
                        + TABLE_TRIP_SEGMENTS
                        + "(vehicle_id, started_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_charge_sessions_session_time ON "
                        + TABLE_CHARGE_SESSIONS
                        + "(session_id, started_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_charge_sessions_vehicle_time ON "
                        + TABLE_CHARGE_SESSIONS
                        + "(vehicle_id, started_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_battery_snapshots_session_time ON "
                        + TABLE_BATTERY_SNAPSHOTS
                        + "(session_id, captured_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_battery_snapshots_vehicle_time ON "
                        + TABLE_BATTERY_SNAPSHOTS
                        + "(vehicle_id, captured_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_cell_snapshots_battery_cell ON "
                        + TABLE_CELL_SNAPSHOTS
                        + "(battery_snapshot_id, cell_index)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_exports_session_time ON "
                        + TABLE_EXPORTS
                        + "(session_id, created_at_ms DESC)");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_exports_vehicle_time ON "
                        + TABLE_EXPORTS
                        + "(vehicle_id, created_at_ms DESC)");
    }
}
