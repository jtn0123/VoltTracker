package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Verifies {@link DataBackup#isVoltTrackerBackup} accepts only real Volt Tracker databases. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DataBackupTest {

    @Test
    public void rejectsAFileThatIsNotSqlite() throws IOException {
        File file = File.createTempFile("not-sqlite", ".db");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("this is plain text, not a database");
        }
        assertFalse(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    @Test
    public void rejectsASqliteDatabaseMissingTheCoreTables() throws IOException {
        File file = tempDbFile("foreign-schema");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE unrelated (id INTEGER)");
        db.close();
        assertFalse(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    @Test
    public void rejectsOldTwoTableSqliteDatabase() throws IOException {
        File file = tempDbFile("old-volt-backup");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE telemetry_samples (_id INTEGER PRIMARY KEY)");
        db.close();
        assertFalse(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    @Test
    public void rejectsCurrentVersionDatabaseMissingRequiredColumns() throws IOException {
        File file = tempDbFile("missing-current-columns");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        createMinimalVoltSchema(db, false, false);
        db.close();
        assertFalse(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    @Test
    public void rejectsDatabaseWithForeignKeyViolations() throws IOException {
        File file = tempDbFile("foreign-key-violations");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        createMinimalVoltSchema(db, true, true);
        db.execSQL(
                "INSERT INTO telemetry_samples (session_id, captured_at_ms, json)"
                        + " VALUES (999, 1, '{}')");
        db.close();
        assertFalse(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    @Test
    public void acceptsASqliteDatabaseWithTheCurrentVoltTrackerSchema() {
        Context context = RuntimeEnvironment.getApplication();
        ObdLocalStore store = new ObdLocalStore(context);
        try {
            store.clearAllData();
            store.checkpoint();
            assertTrue(DataBackup.isVoltTrackerBackup(store.getDatabaseFile()));
        } finally {
            store.close();
        }
    }

    @Test
    public void debugExportWritesJsonFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DataBackup backup = new DataBackup(context);

        JSONObject result =
                new JSONObject(
                        backup.exportDebugBundle(
                                "{\"session\":{\"state\":\"idle\"}}", "{\"sampleCount\":0}"));

        assertTrue(result.optBoolean("ok"));
        File file = new File(result.getString("path"));
        assertTrue(file.exists());
        JSONObject exported =
                new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertNotNull(exported.optJSONObject("appState"));
        assertNotNull(exported.optJSONObject("storage"));
        file.delete();
    }

    private static File tempDbFile(String prefix) throws IOException {
        File file = File.createTempFile(prefix, ".db");
        // SQLiteDatabase.openOrCreateDatabase needs to create the file itself.
        Files.delete(file.toPath());
        return file;
    }

    private static void createMinimalVoltSchema(
            SQLiteDatabase db, boolean includeHvPackColumns, boolean includeForeignKey) {
        db.setVersion(8);
        db.execSQL(
                "CREATE TABLE obd_sessions ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "mode TEXT NOT NULL,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "sample_count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL(
                "CREATE TABLE telemetry_samples ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "session_id INTEGER NOT NULL,"
                        + "captured_at_ms INTEGER NOT NULL,"
                        + (includeHvPackColumns ? "pack_voltage REAL,pack_current_a REAL," : "")
                        + "json TEXT NOT NULL"
                        + (includeForeignKey
                                ? ",FOREIGN KEY(session_id) REFERENCES obd_sessions(_id)"
                                : "")
                        + ")");
        db.execSQL(
                "CREATE TABLE status_events ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "occurred_at_ms INTEGER NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "payload TEXT NOT NULL)");
        db.execSQL(
                "CREATE TABLE adapter_history ("
                        + "adapter_key TEXT PRIMARY KEY,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "last_status TEXT)");
        db.execSQL(
                "CREATE TABLE pid_observations (_id INTEGER PRIMARY KEY, observed_at_ms INTEGER)");
        db.execSQL(
                "CREATE TABLE diagnostic_codes ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "dtc TEXT NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL)");
        db.execSQL(
                "CREATE TABLE location_samples ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "session_id INTEGER NOT NULL,"
                        + "captured_at_ms INTEGER NOT NULL,"
                        + "latitude REAL NOT NULL,"
                        + "longitude REAL NOT NULL)");
        db.execSQL(
                "CREATE TABLE vehicles ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "vin_hash TEXT,"
                        + "vin_redacted TEXT,"
                        + "last_seen_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE field_capabilities (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE trip_segments (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE charge_sessions (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE battery_snapshots (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE cell_snapshots (_id INTEGER PRIMARY KEY)");
        db.execSQL(
                "CREATE TABLE exports ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "export_type TEXT NOT NULL,"
                        + "status TEXT NOT NULL)");
    }
}
