package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

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
    public void acceptsASqliteDatabaseWithTheCoreTables() throws IOException {
        File file = tempDbFile("volt-backup");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE telemetry_samples (_id INTEGER PRIMARY KEY)");
        db.close();
        assertTrue(DataBackup.isVoltTrackerBackup(file));
        file.delete();
    }

    private static File tempDbFile(String prefix) throws IOException {
        File file = File.createTempFile(prefix, ".db");
        // SQLiteDatabase.openOrCreateDatabase needs to create the file itself.
        Files.delete(file.toPath());
        return file;
    }
}
