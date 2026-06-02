package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Exercises {@link DatabaseMerger} against two independent real SQLite databases: id remapping,
 * session dedup by start time, vehicle merge by key, foreign-key integrity after the merge, and
 * idempotency. Both handles run with foreign keys enabled (via {@link VoltTrackerDb#onConfigure}),
 * so a remap bug would surface as an insert/constraint failure here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DatabaseMergerTest {

    private VoltTrackerDb liveHelper;
    private VoltTrackerDb donorHelper;
    private SQLiteDatabase live;
    private SQLiteDatabase donor;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        liveHelper = new VoltTrackerDb(context, "merge-live.db");
        donorHelper = new VoltTrackerDb(context, "merge-donor.db");
        live = liveHelper.getWritableDatabase();
        donor = donorHelper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        Context context = RuntimeEnvironment.getApplication();
        if (liveHelper != null) {
            liveHelper.close();
        }
        if (donorHelper != null) {
            donorHelper.close();
        }
        context.deleteDatabase("merge-live.db");
        context.deleteDatabase("merge-donor.db");
    }

    @Test
    public void mergesNewSessionAndReattachesTelemetry() {
        long liveSession = insertSession(live, 1000L);
        insertTelemetry(live, liveSession, 1000L);

        long donorSession = insertSession(donor, 2000L);
        insertTelemetry(donor, donorSession, 2000L);
        insertTelemetry(donor, donorSession, 2001L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(1, result.sessionsAdded);
        assertEquals(0, result.sessionsSkipped);
        assertEquals(2, count(live, VoltTrackerDb.TABLE_SESSIONS));
        // 1 live + 2 donor telemetry rows, all with a resolvable session_id.
        assertEquals(3, count(live, VoltTrackerDb.TABLE_TELEMETRY));
        assertEquals(
                0,
                count(
                        live,
                        VoltTrackerDb.TABLE_TELEMETRY
                                + " t WHERE NOT EXISTS (SELECT 1 FROM "
                                + VoltTrackerDb.TABLE_SESSIONS
                                + " s WHERE s._id = t.session_id)"));
        assertForeignKeysIntact(live);
    }

    @Test
    public void skipsDuplicateSessionAndItsChildren() {
        long liveSession = insertSession(live, 1000L);
        insertTelemetry(live, liveSession, 1000L);

        // Donor has the same start time (a duplicate drive) plus a genuinely new one.
        long dupSession = insertSession(donor, 1000L);
        insertTelemetry(donor, dupSession, 1000L);
        insertTelemetry(donor, dupSession, 1001L);
        long newSession = insertSession(donor, 3000L);
        insertTelemetry(donor, newSession, 3000L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(1, result.sessionsAdded);
        assertEquals(1, result.sessionsSkipped);
        assertEquals(2, count(live, VoltTrackerDb.TABLE_SESSIONS));
        // Live's 1 + only the new donor session's 1 telemetry row. The duplicate's 2 are dropped.
        assertEquals(2, count(live, VoltTrackerDb.TABLE_TELEMETRY));
        assertForeignKeysIntact(live);
    }

    @Test
    public void mergesVehicleByKeyAndRepointsTrip() {
        long liveVehicle = insertVehicle(live, "VH1");
        long donorVehicle = insertVehicle(donor, "VH1"); // same natural key -> should merge
        long donorSession = insertSession(donor, 5000L);
        insertTripSegment(donor, donorSession, donorVehicle, 5000L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(0, result.vehiclesAdded);
        assertEquals(1, result.vehiclesMerged);
        // Still exactly one vehicle row, and the imported trip points at the live vehicle id.
        assertEquals(1, count(live, VoltTrackerDb.TABLE_VEHICLES));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TRIP_SEGMENTS));
        assertEquals(
                1,
                count(
                        live,
                        VoltTrackerDb.TABLE_TRIP_SEGMENTS + " WHERE vehicle_id = " + liveVehicle));
        assertForeignKeysIntact(live);
    }

    @Test
    public void mergeIntoEmptyImportsEverything() {
        long donorSession = insertSession(donor, 7000L);
        insertTelemetry(donor, donorSession, 7000L);
        insertVehicle(donor, "VHX");

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(1, result.sessionsAdded);
        assertEquals(1, result.vehiclesAdded);
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_VEHICLES));
        assertForeignKeysIntact(live);
    }

    @Test
    public void mergingTwiceIsIdempotent() {
        long donorSession = insertSession(donor, 8000L);
        insertTelemetry(donor, donorSession, 8000L);

        DatabaseMerger.MergeResult first = DatabaseMerger.merge(live, donor);
        DatabaseMerger.MergeResult second = DatabaseMerger.merge(live, donor);

        assertTrue(first.ok);
        assertTrue(second.ok);
        assertEquals(1, first.sessionsAdded);
        assertEquals(0, second.sessionsAdded);
        assertEquals(1, second.sessionsSkipped);
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY));
    }

    @Test
    public void summaryReadsCleanlyForSkippedDuplicates() {
        insertSession(live, 1000L);
        insertSession(donor, 1000L);
        insertSession(donor, 2000L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertEquals("Merged backup - 1 new session (1 duplicate skipped).", result.summary());
    }

    @Test
    public void summaryUsesPluralForMultipleSessions() {
        insertSession(donor, 1000L);
        insertSession(donor, 2000L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertEquals("Merged backup - 2 new sessions.", result.summary());
    }

    @Test
    public void summaryDoesNotReadAsNoOpForVehicleOnlyImport() {
        // A backup that contributes only a vehicle (no sessions) must not say "0 sessions" as
        // though nothing was imported.
        insertVehicle(donor, "VHONLY");

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(0, result.sessionsAdded);
        assertEquals(1, result.vehiclesAdded);
        assertEquals("Merged backup - no new sessions, 1 new vehicle.", result.summary());
    }

    @Test
    public void nullDonorHandleFailsCleanly() {
        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, null);

        assertFalse(result.ok);
        assertEquals("Merge failed - database handle unavailable.", result.summary());
    }

    @Test
    public void mergesEveryChildTableAndKeepsForeignKeysIntact() {
        long vehicle = insertVehicle(donor, "VHALL");
        long session = insertSession(donor, 9000L);
        insertTelemetry(donor, session, 9000L);
        insertEvent(donor, session, 9001L);
        insertPidObservation(donor, session, 9002L);
        insertLocationSample(donor, session, 9003L);
        insertFieldCapability(donor, vehicle);
        insertTripSegment(donor, session, vehicle, 9000L);
        insertChargeSession(donor, session, vehicle, 9000L);
        long battery = insertBatterySnapshot(donor, session, vehicle, 9004L);
        insertCellSnapshot(donor, battery, 0);
        insertCellSnapshot(donor, battery, 1);
        insertExport(donor, session, vehicle, 9005L);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_EVENTS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_PID_OBSERVATIONS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_LOCATION_SAMPLES));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_FIELD_CAPABILITIES));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TRIP_SEGMENTS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_CHARGE_SESSIONS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS));
        assertEquals(2, count(live, VoltTrackerDb.TABLE_CELL_SNAPSHOTS));
        assertEquals(1, count(live, VoltTrackerDb.TABLE_EXPORTS));
        assertForeignKeysIntact(live);
    }

    @Test
    public void cellSnapshotsOfSkippedSessionAreDropped() {
        // Duplicate session by start time -> its battery snapshot is not imported, so its cell
        // snapshots (NOT NULL parent) must be dropped rather than orphaned.
        insertSession(live, 1000L);
        long dupSession = insertSession(donor, 1000L);
        long battery = insertBatterySnapshot(donor, dupSession, null, 1001L);
        insertCellSnapshot(donor, battery, 0);

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(0, count(live, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS));
        assertEquals(0, count(live, VoltTrackerDb.TABLE_CELL_SNAPSHOTS));
        assertForeignKeysIntact(live);
    }

    @Test
    public void upsertsAdapterHistoryAndDiagnosticCodesOnNaturalKeys() {
        // Same natural keys on both sides exercise the merge/update branch; donor-only rows
        // exercise the insert branch.
        insertAdapterHistory(live, "AD:1", 5, 100L);
        insertAdapterHistory(donor, "AD:1", 3, 200L); // merges into the live AD:1 row
        insertAdapterHistory(donor, "AD:2", 1, 50L); // new -> inserted

        insertDiagnosticCode(live, "MOD", "P0001", "current", 2, 100L);
        insertDiagnosticCode(donor, "MOD", "P0001", "current", 4, 300L); // merges
        insertDiagnosticCode(donor, "MOD", "P0002", "pending", 1, 80L); // new -> inserted

        DatabaseMerger.MergeResult result = DatabaseMerger.merge(live, donor);

        assertTrue(result.ok);
        assertEquals(2, count(live, VoltTrackerDb.TABLE_ADAPTER_HISTORY));
        assertEquals(2, count(live, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES));
        // Counters summed on the shared keys.
        assertEquals(
                8,
                scalar(
                        live,
                        "SELECT connect_count FROM "
                                + VoltTrackerDb.TABLE_ADAPTER_HISTORY
                                + " WHERE adapter_key = 'AD:1'"));
        assertEquals(
                6,
                scalar(
                        live,
                        "SELECT seen_count FROM "
                                + VoltTrackerDb.TABLE_DIAGNOSTIC_CODES
                                + " WHERE dtc = 'P0001'"));
        assertForeignKeysIntact(live);
    }

    @Test
    public void adapterHistoryUpsertKeepsMinFirstAndMaxLastSeen() {
        // live AD:1 seen [first=1, last=100]; donor AD:1 seen [first=1, last=200] (newer).
        insertAdapterHistoryFull(live, "AD:1", 5, /*first*/ 50L, /*last*/ 100L);
        insertAdapterHistoryFull(donor, "AD:1", 3, /*first*/ 10L, /*last*/ 200L);

        assertTrue(DatabaseMerger.merge(live, donor).ok);

        assertEquals(
                "first_seen_ms must be the earliest across both",
                10,
                scalar(
                        live,
                        "SELECT first_seen_ms FROM "
                                + VoltTrackerDb.TABLE_ADAPTER_HISTORY
                                + " WHERE adapter_key = 'AD:1'"));
        assertEquals(
                "last_seen_ms must be the latest across both",
                200,
                scalar(
                        live,
                        "SELECT last_seen_ms FROM "
                                + VoltTrackerDb.TABLE_ADAPTER_HISTORY
                                + " WHERE adapter_key = 'AD:1'"));
    }

    @Test
    public void remapsTripSegmentSamplePointersToTheMergedTelemetryRows() {
        // A donor trip segment references specific donor telemetry rows via start/end_sample_id.
        // After merge those pointers must resolve to the NEWLY-inserted telemetry rows (the donor
        // ids would otherwise collide with unrelated live rows), and stay foreign-key valid.
        long donorSession = insertSession(donor, 6000L);
        long startTel = insertTelemetry(donor, donorSession, 6000L); // captured_at_ms 6000
        insertTelemetry(donor, donorSession, 6500L);
        long endTel = insertTelemetry(donor, donorSession, 7000L); // captured_at_ms 7000
        insertTripSegmentWithSamples(donor, donorSession, 6000L, startTel, endTel);

        // Pre-seed the live DB with unrelated telemetry so donor ids (1..3) collide with live ids.
        long liveSession = insertSession(live, 100L);
        insertTelemetry(live, liveSession, 100L);
        insertTelemetry(live, liveSession, 200L);

        assertTrue(DatabaseMerger.merge(live, donor).ok);
        assertForeignKeysIntact(live);

        // The merged trip segment's start/end pointers must land on telemetry rows whose
        // captured_at_ms match the donor's start (6000) and end (7000) samples — proving the
        // remap tracked the right rows, not stale donor ids.
        assertEquals(
                6000,
                scalar(
                        live,
                        "SELECT t.captured_at_ms FROM "
                                + VoltTrackerDb.TABLE_TRIP_SEGMENTS
                                + " s JOIN "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " t ON t._id = s.start_sample_id"));
        assertEquals(
                7000,
                scalar(
                        live,
                        "SELECT t.captured_at_ms FROM "
                                + VoltTrackerDb.TABLE_TRIP_SEGMENTS
                                + " s JOIN "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " t ON t._id = s.end_sample_id"));
    }

    // ---- fixtures ---------------------------------------------------------------

    private static long insertSession(SQLiteDatabase db, long startedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("mode", "obd");
        cv.put("started_at_ms", startedAtMs);
        cv.put("status", "complete");
        cv.put("sample_count", 0);
        cv.put("created_at_ms", startedAtMs);
        return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv);
    }

    private static long insertTelemetry(SQLiteDatabase db, long sessionId, long capturedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("captured_at_ms", capturedAtMs);
        cv.put("speed_kph", 40);
        cv.put("json", "{}");
        return db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, cv);
    }

    private static void insertTripSegmentWithSamples(
            SQLiteDatabase db,
            long sessionId,
            long startedAtMs,
            long startSampleId,
            long endSampleId) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("started_at_ms", startedAtMs);
        cv.put("route_available", 1);
        cv.put("start_sample_id", startSampleId);
        cv.put("end_sample_id", endSampleId);
        cv.put("created_at_ms", startedAtMs);
        db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, cv);
    }

    private static long insertVehicle(SQLiteDatabase db, String key) {
        ContentValues cv = new ContentValues();
        cv.put("vehicle_key", key);
        cv.put("display_name", "Test " + key);
        cv.put("first_seen_ms", 1L);
        cv.put("last_seen_ms", 2L);
        cv.put("created_at_ms", 1L);
        cv.put("updated_at_ms", 2L);
        return db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv);
    }

    private static void insertTripSegment(
            SQLiteDatabase db, long sessionId, long vehicleId, long startedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("vehicle_id", vehicleId);
        cv.put("started_at_ms", startedAtMs);
        cv.put("route_available", 0);
        cv.put("created_at_ms", startedAtMs);
        db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, cv);
    }

    private static void insertEvent(SQLiteDatabase db, long sessionId, long occurredAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("occurred_at_ms", occurredAtMs);
        cv.put("kind", "status");
        cv.put("blocked", 0);
        cv.put("payload", "{}");
        db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, cv);
    }

    private static void insertPidObservation(SQLiteDatabase db, long sessionId, long observedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("observed_at_ms", observedAtMs);
        cv.put("pid", "0105");
        cv.put("json", "{}");
        db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, cv);
    }

    private static void insertLocationSample(SQLiteDatabase db, long sessionId, long capturedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("captured_at_ms", capturedAtMs);
        cv.put("latitude", 37.0);
        cv.put("longitude", -122.0);
        cv.put("json", "{}");
        db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, cv);
    }

    private static void insertFieldCapability(SQLiteDatabase db, long vehicleId) {
        ContentValues cv = new ContentValues();
        cv.put("vehicle_id", vehicleId);
        cv.put("command", "0105");
        cv.put("supported", 1);
        cv.put("response_count", 1);
        cv.put("first_seen_ms", 1L);
        cv.put("last_seen_ms", 2L);
        db.insertOrThrow(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, cv);
    }

    private static void insertChargeSession(
            SQLiteDatabase db, long sessionId, long vehicleId, long startedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("vehicle_id", vehicleId);
        cv.put("started_at_ms", startedAtMs);
        cv.put("interrupted", 0);
        cv.put("created_at_ms", startedAtMs);
        db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, cv);
    }

    private static long insertBatterySnapshot(
            SQLiteDatabase db, long sessionId, Long vehicleId, long capturedAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        if (vehicleId != null) {
            cv.put("vehicle_id", vehicleId);
        }
        cv.put("captured_at_ms", capturedAtMs);
        cv.put("created_at_ms", capturedAtMs);
        return db.insertOrThrow(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, cv);
    }

    private static void insertCellSnapshot(SQLiteDatabase db, long batterySnapshotId, int index) {
        ContentValues cv = new ContentValues();
        cv.put("battery_snapshot_id", batterySnapshotId);
        cv.put("cell_index", index);
        cv.put("voltage", 3.7);
        db.insertOrThrow(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, cv);
    }

    private static void insertExport(
            SQLiteDatabase db, long sessionId, long vehicleId, long createdAtMs) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("vehicle_id", vehicleId);
        cv.put("created_at_ms", createdAtMs);
        cv.put("export_type", "csv");
        cv.put("status", "complete");
        db.insertOrThrow(VoltTrackerDb.TABLE_EXPORTS, null, cv);
    }

    private static void insertAdapterHistory(
            SQLiteDatabase db, String key, int connectCount, long lastSeenMs) {
        insertAdapterHistoryFull(db, key, connectCount, 1L, lastSeenMs);
    }

    private static void insertAdapterHistoryFull(
            SQLiteDatabase db, String key, int connectCount, long firstSeenMs, long lastSeenMs) {
        ContentValues cv = new ContentValues();
        cv.put("adapter_key", key);
        cv.put("connect_count", connectCount);
        cv.put("scan_count", 0);
        cv.put("demo_count", 0);
        cv.put("sample_count", 0);
        cv.put("first_seen_ms", firstSeenMs);
        cv.put("last_seen_ms", lastSeenMs);
        db.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv);
    }

    private static void insertDiagnosticCode(
            SQLiteDatabase db,
            String moduleKey,
            String dtc,
            String status,
            int seenCount,
            long lastSeenMs) {
        ContentValues cv = new ContentValues();
        cv.put("module_key", moduleKey);
        cv.put("dtc", dtc);
        cv.put("status", status);
        cv.put("seen_count", seenCount);
        cv.put("first_seen_ms", 1L);
        cv.put("last_seen_ms", lastSeenMs);
        cv.put("json", "{}");
        db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv);
    }

    private static int count(SQLiteDatabase db, String tableAndMaybeWhere) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + tableAndMaybeWhere, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    private static int scalar(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    private static void assertForeignKeysIntact(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) {
            assertFalse("foreign_key_check reported violations after merge", c.moveToFirst());
        }
    }
}
