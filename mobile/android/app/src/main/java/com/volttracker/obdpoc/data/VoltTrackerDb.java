package com.volttracker.obdpoc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class VoltTrackerDb extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "volttracker_obd_poc.db";
    static final int DATABASE_VERSION = 3;

    static final String TABLE_SESSIONS = "obd_sessions";
    static final String TABLE_TELEMETRY = "telemetry_samples";
    static final String TABLE_EVENTS = "status_events";
    static final String TABLE_ADAPTER_HISTORY = "adapter_history";
    static final String TABLE_PID_OBSERVATIONS = "pid_observations";
    static final String TABLE_LOCATION_SAMPLES = "location_samples";

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
        db.execSQL("CREATE TABLE " + TABLE_SESSIONS + " ("
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

        db.execSQL("CREATE TABLE " + TABLE_TELEMETRY + " ("
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
                + "raw TEXT,"
                + "json TEXT NOT NULL,"
                + "FOREIGN KEY(session_id) REFERENCES " + TABLE_SESSIONS + "(_id) ON DELETE CASCADE"
                + ")");

        db.execSQL("CREATE TABLE " + TABLE_EVENTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id INTEGER,"
                + "occurred_at_ms INTEGER NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "state TEXT,"
                + "detail TEXT,"
                + "blocked INTEGER NOT NULL DEFAULT 0,"
                + "payload TEXT NOT NULL,"
                + "FOREIGN KEY(session_id) REFERENCES " + TABLE_SESSIONS + "(_id) ON DELETE SET NULL"
                + ")");

        db.execSQL("CREATE TABLE " + TABLE_ADAPTER_HISTORY + " ("
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

        db.execSQL("CREATE INDEX idx_sessions_started ON " + TABLE_SESSIONS
                + "(started_at_ms DESC)");
        db.execSQL("CREATE INDEX idx_telemetry_session_time ON " + TABLE_TELEMETRY
                + "(session_id, captured_at_ms DESC)");
        db.execSQL("CREATE INDEX idx_events_session_time ON " + TABLE_EVENTS
                + "(session_id, occurred_at_ms DESC)");
        db.execSQL("CREATE INDEX idx_adapter_history_seen ON " + TABLE_ADAPTER_HISTORY
                + "(last_seen_ms DESC)");
        createObservationTables(db);
        createObservationIndexes(db);
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
    }

    private static void createObservationTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PID_OBSERVATIONS + " ("
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
                + "FOREIGN KEY(session_id) REFERENCES " + TABLE_SESSIONS + "(_id) ON DELETE CASCADE"
                + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_LOCATION_SAMPLES + " ("
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
                + "FOREIGN KEY(session_id) REFERENCES " + TABLE_SESSIONS + "(_id) ON DELETE CASCADE"
                + ")");
    }

    private static void createObservationIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pid_observations_session_time ON "
                + TABLE_PID_OBSERVATIONS + "(session_id, observed_at_ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pid_observations_command_header ON "
                + TABLE_PID_OBSERVATIONS + "(command, header, observed_at_ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_samples_session_time ON "
                + TABLE_LOCATION_SAMPLES + "(session_id, captured_at_ms DESC)");
    }
}
