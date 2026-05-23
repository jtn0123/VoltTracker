package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.data.ObdSessionRecord;
import com.volttracker.obdpoc.data.StatusEventRecord;
import com.volttracker.obdpoc.data.TelemetrySampleRecord;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * End-to-end backup/restore round-trip: seed an {@link ObdLocalStore}, export it via {@link
 * DataBackup#buildBackupFile}, hand the file back through {@link DataBackup#stageRestoreFile} (the
 * SAF URI path that {@link BackupController} uses in production), swap the staged file into the
 * live DB path, and re-open the store to confirm every seeded row survived.
 *
 * <p>The other tests in this suite cover only the schema sniff in {@link
 * DataBackup#isVoltTrackerBackup}; this one exercises the file IO + content-resolver staging +
 * post-restore reopen that the existing tests miss.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupRoundTripTest {

    private Context context;
    private DataBackup dataBackup;
    private ObdLocalStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        dataBackup = new DataBackup(context);
        store = new ObdLocalStore(context);
        store.clearAllData();
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static JSONObject telemetrySample(
            int speedKph, int rpm, double lat, double lng, long atMs) throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", rpm);
        sample.put("latitude", lat);
        sample.put("longitude", lng);
        sample.put("updatedAt", atMs);
        return sample;
    }

    /**
     * Stages {@code source} as if the user had picked it from the file picker. {@link
     * DataBackup#stageRestoreFile} reads from {@link ContentResolver#openInputStream}, so we hand
     * Robolectric a real {@link FileInputStream} keyed to the URI we return.
     */
    private Uri registerAsSafUri(File source) throws IOException {
        Uri uri = Uri.parse("content://test/" + source.getName());
        shadowOf(context.getContentResolver())
                .registerInputStream(uri, new FileInputStream(source));
        return uri;
    }

    private Uri registerBytesAsSafUri(String suffix, byte[] bytes) {
        Uri uri = Uri.parse("content://test/" + suffix);
        shadowOf(context.getContentResolver())
                .registerInputStream(uri, new ByteArrayInputStream(bytes));
        return uri;
    }

    /**
     * Mirrors the file-swap that {@link BackupController#applyRestore} performs after staging:
     * close the live store, copy the staged file over the live DB file, drop the WAL/SHM sidecars,
     * then re-open the store. The test calls this instead of going through BackupController so we
     * don't need a MainActivity instance.
     */
    private void swapStagedFileIntoLiveDb(File staged) throws IOException {
        File dbFile = store.getDatabaseFile();
        store.close();
        store = null;
        DataBackup.copyFile(staged, dbFile);
        DataBackup.deleteIfExists(new File(dbFile.getPath() + "-wal"));
        DataBackup.deleteIfExists(new File(dbFile.getPath() + "-shm"));
        store = new ObdLocalStore(context);
    }

    private static File tempDbFile(String prefix) throws IOException {
        File file = File.createTempFile(prefix, ".db");
        // SQLiteDatabase.openOrCreateDatabase needs to create the file itself.
        Files.delete(file.toPath());
        return file;
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Happy path: seed one row per high-cardinality table, export, restore, verify every seeded row
     * came back. This is the exact path a user takes when they back up on one install and restore
     * on a fresh install.
     */
    @Test
    public void roundTripPreservesAllSeededRows() throws Exception {
        // -- seed --
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter X", 1_000L);
        store.recordTelemetry(sessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L));
        store.recordTelemetry(sessionId, telemetrySample(45, 1550, 32.71, -117.10, 1_200L));
        store.recordTelemetry(sessionId, telemetrySample(50, 1600, 32.72, -117.10, 1_300L));
        store.recordStatus(sessionId, "ready", "connected", false, new JSONObject());
        store.recordPidObservation(
                sessionId,
                1_400L,
                "0105",
                "7E0",
                "0105",
                "Engine coolant temp",
                "85",
                85.0,
                "C",
                "0105",
                "41 05 55");
        store.recordLocationSample(
                sessionId, 1_500L, "gps", 32.73, -117.10, 5.0, null, null, null, null, null);
        store.finalizeSession(
                sessionId,
                ObdLocalStore.STATUS_COMPLETE,
                2_000L,
                "0100,0105",
                "AA:BB:CC",
                "Adapter X",
                "obd",
                3,
                "trip end");

        JSONObject before = store.getStorageSummary();
        assertEquals(1, before.optInt("sessionCount"));
        assertEquals(3L, before.optLong("sampleCount"));
        assertTrue("seeded status event", before.optLong("eventCount") >= 1L);
        assertEquals(1L, before.optLong("pidObservationCount"));
        assertEquals(1L, before.optLong("locationSampleCount"));

        // -- export --
        File backup = dataBackup.buildBackupFile(store);
        assertNotNull("buildBackupFile should produce a file", backup);
        assertTrue(backup.exists());
        assertTrue("backup must be a real Volt Tracker DB", DataBackup.isVoltTrackerBackup(backup));

        // -- stage via SAF URI (the production path) --
        Uri uri = registerAsSafUri(backup);
        File staged = dataBackup.stageRestoreFile(uri);
        assertNotNull("stageRestoreFile should accept the produced backup", staged);

        // -- swap into the live DB path, reopen --
        swapStagedFileIntoLiveDb(staged);
        staged.delete();

        // -- verify every seeded row survived --
        ObdSessionRecord session = store.getSession(sessionId);
        assertNotNull("session row should survive restore", session);
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session.status);
        assertEquals(2_000L, session.endedAtMs);
        assertEquals("0100,0105", session.supportedPids);

        List<TelemetrySampleRecord> telemetry = store.getRecentTelemetry(sessionId, 100);
        assertEquals(
                "all telemetry rows should round-trip through the backup", 3, telemetry.size());

        List<StatusEventRecord> events = store.getRecentEvents(sessionId, 100);
        assertTrue("status event should round-trip", events.size() >= 1);

        JSONObject after = store.getStorageSummary();
        assertEquals(1, after.optInt("sessionCount"));
        assertEquals(3L, after.optLong("sampleCount"));
        assertEquals(before.optLong("eventCount"), after.optLong("eventCount"));
        assertEquals(1L, after.optLong("pidObservationCount"));
        assertEquals(1L, after.optLong("locationSampleCount"));
    }

    /**
     * Restore over existing data: production performs a full file replace (not a merge), so the
     * post-restore store must contain only the backup's rows and none of the data that was in the
     * target store before the restore.
     */
    @Test
    public void restoreReplacesExistingDataInTargetStore() throws Exception {
        // -- seed the source (this is what we'll back up) --
        long sourceSessionId = store.startSession("obd", "AA:AA:AA", "Source", 1_000L);
        store.recordTelemetry(sourceSessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L));
        store.finalizeSession(
                sourceSessionId,
                ObdLocalStore.STATUS_COMPLETE,
                2_000L,
                "0100",
                "AA:AA:AA",
                "Source",
                "obd",
                1,
                "source done");

        File backup = dataBackup.buildBackupFile(store);
        assertNotNull(backup);

        // -- now overwrite the live store with completely different data, as if a different
        //    install accumulated its own sessions before the user clicked "restore" --
        store.clearAllData();
        long throwawaySessionId = store.startSession("obd", "ZZ:ZZ:ZZ", "Other", 5_000L);
        store.recordTelemetry(throwawaySessionId, telemetrySample(99, 9999, 0.0, 0.0, 5_100L));
        store.recordStatus(throwawaySessionId, "blocked", "before restore", true, new JSONObject());
        assertEquals(1, store.getStorageSummary().optInt("sessionCount"));

        // -- restore --
        Uri uri = registerAsSafUri(backup);
        File staged = dataBackup.stageRestoreFile(uri);
        assertNotNull(staged);
        swapStagedFileIntoLiveDb(staged);
        staged.delete();

        // -- post-restore state: only the source session is present; the throwaway row is gone --
        ObdSessionRecord restoredSource = store.getSession(sourceSessionId);
        assertNotNull("source session must be present after restore", restoredSource);
        assertEquals("AA:AA:AA", restoredSource.adapterAddress);

        ObdSessionRecord throwaway = store.getSession(throwawaySessionId);
        assertNull("throwaway session from the pre-restore target store must be wiped", throwaway);

        assertEquals(1, store.getStorageSummary().optInt("sessionCount"));
        assertEquals(1L, store.getStorageSummary().optLong("sampleCount"));
    }

    /**
     * A non-SQLite file passed to {@link DataBackup#stageRestoreFile} must be rejected (return
     * null) and must not touch the live store. We seed the target store first and verify it is
     * untouched after the rejected attempt — a regression here would mean a corrupt user file
     * silently trashes their data.
     */
    @Test
    public void restoreOfInvalidFileIsRejectedAndLeavesTargetStoreIntact() throws Exception {
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter", 1_000L);
        store.recordTelemetry(sessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L));
        long beforeSamples = store.getStorageSummary().optLong("sampleCount");
        long beforeSessions = store.getStorageSummary().optInt("sessionCount");

        Uri uri =
                registerBytesAsSafUri(
                        "junk.bin",
                        "not a database, just plain text".getBytes(StandardCharsets.UTF_8));
        File staged = dataBackup.stageRestoreFile(uri);

        assertNull("stageRestoreFile must reject a non-SQLite file", staged);
        // stageRestoreFile cleans up its temp file when it rejects.
        assertFalse(new File(context.getCacheDir(), "restore-tmp.db").exists());

        // The live store is untouched.
        assertEquals(beforeSessions, store.getStorageSummary().optInt("sessionCount"));
        assertEquals(beforeSamples, store.getStorageSummary().optLong("sampleCount"));
        assertNotNull(store.getSession(sessionId));
    }

    /**
     * A SQLite file with a foreign schema must also be rejected — the schema sniff is the only
     * thing that prevents another app's database (or a stale, pre-migration DB from a different
     * tool) from being copied over the live file and breaking every query the app makes.
     */
    @Test
    public void restoreOfForeignSchemaIsRejected() throws Exception {
        // Build a real SQLite file with a wrong schema.
        File foreign = tempDbFile("foreign-schema");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(foreign, null);
        try {
            db.execSQL("CREATE TABLE wrong (id INTEGER PRIMARY KEY, value TEXT)");
            db.execSQL("INSERT INTO wrong (value) VALUES ('not us')");
        } finally {
            db.close();
        }

        // The low-level sniff rejects it directly.
        assertFalse(
                "isVoltTrackerBackup must reject a SQLite file with foreign tables",
                DataBackup.isVoltTrackerBackup(foreign));

        // And the SAF path rejects it too, since stageRestoreFile pipes through the same sniff.
        Uri uri = registerAsSafUri(foreign);
        File staged = dataBackup.stageRestoreFile(uri);
        assertNull("stageRestoreFile must reject a foreign-schema SQLite file", staged);
        assertFalse(new File(context.getCacheDir(), "restore-tmp.db").exists());

        foreign.delete();
    }

    /**
     * Verifies the file path used by {@link DataBackup#buildBackupFile} lives under the cache
     * directory so the {@code FileProvider} share-sheet hand-off can grant a content URI for it.
     */
    @Test
    public void buildBackupFileWritesToCacheDir() throws Exception {
        store.startSession("obd", "AA:BB:CC", "Adapter", 1_000L);

        File backup = dataBackup.buildBackupFile(store);
        assertNotNull(backup);
        assertTrue(backup.exists());
        assertTrue(
                "backup must live under the app cache dir so it's eligible for the file provider",
                backup.getAbsolutePath().startsWith(context.getCacheDir().getAbsolutePath()));
    }

    /**
     * A {@link DataBackup#stageRestoreFile} call with a URI that has no registered stream returns
     * null — the SAF handoff can fail (file picked from a now-revoked URI permission) and the
     * stager must surface that as a rejected restore, not a crash.
     */
    @Test
    public void stageRestoreFileReturnsNullForUnreadableUri() {
        Uri uri = Uri.parse("content://test/missing.db");
        // Intentionally do NOT register a stream for this URI.
        File staged = dataBackup.stageRestoreFile(uri);
        assertNull(staged);
        assertFalse(new File(context.getCacheDir(), "restore-tmp.db").exists());
    }

    @Test
    public void unrelatedFileCleanedUpOnBuildBackup() throws IOException {
        // buildBackupFile clears the backups dir before each build — drop a sentinel into the dir
        // ahead of time and confirm it doesn't survive the next build.
        File backupsDir = new File(context.getCacheDir(), "backups");
        assertTrue(backupsDir.mkdirs() || backupsDir.isDirectory());
        File sentinel = new File(backupsDir, "leftover.db");
        try (FileWriter writer = new FileWriter(sentinel)) {
            writer.write("stale");
        }
        assertTrue(sentinel.exists());

        store.startSession("obd", "AA:BB:CC", "Adapter", 1_000L);
        File backup = dataBackup.buildBackupFile(store);
        assertNotNull(backup);
        assertFalse("stale file should have been cleared", sentinel.exists());
    }
}
