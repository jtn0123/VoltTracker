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
}
