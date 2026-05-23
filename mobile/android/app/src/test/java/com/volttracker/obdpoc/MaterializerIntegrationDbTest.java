package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.ChargeSessionMaterializer;
import com.volttracker.obdpoc.materialize.MaterializerInput;
import com.volttracker.obdpoc.materialize.Trip;
import com.volttracker.obdpoc.materialize.TripMaterializer;
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
 * Integration test for the materializer round-trip: seed a real {@link ObdLocalStore} with one
 * session's worth of telemetry and location samples, run the materializers, persist their output,
 * and confirm the {@code trip_segments} / {@code charge_sessions} rows actually landed.
 *
 * <p>The "flag off" half of the original spec is documented but not exercised: {@link
 * BuildFlags#MATERIALIZE_SESSIONS} is a {@code static final boolean}, so its branch is dead code at
 * compile time and a test that toggled it would prove nothing about the production binary. The
 * intent of the flag is to give us a one-line revert if the heuristic misfires in the field; that
 * is verified by inspecting the call site, not by a runtime test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaterializerIntegrationDbTest {

    private static final long T_BASE = 1_700_000_000_000L;
    private static final long ONE_MINUTE_MS = 60_000L;

    private ObdLocalStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        store = new ObdLocalStore(context);
        store.clearAllData();
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    public void materializersWriteTripAndChargeRowsForASeededSession() throws Exception {
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter", T_BASE);

        // Seed a driving window: 12 location samples (>= OBSERVED threshold) over ~5.5 minutes.
        for (int i = 0; i < 12; i++) {
            store.recordLocationSample(
                    sessionId,
                    T_BASE + i * 30 * 1_000L,
                    "gps",
                    32.700000 + i * 0.0015,
                    -117.100000,
                    5.0,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        // Telemetry rows during the drive — voltage low, speed high.
        for (int i = 0; i < 12; i++) {
            store.recordTelemetry(sessionId, telemetry(T_BASE + i * 30 * 1_000L, 45, 1500, 12.3));
        }

        // Then a charge window: 10 minutes of high-voltage stationary telemetry.
        long chargeStart = T_BASE + 10 * ONE_MINUTE_MS;
        for (int i = 0; i <= 10; i++) {
            store.recordTelemetry(
                    sessionId, telemetry(chargeStart + i * ONE_MINUTE_MS, 0, 0, 14.4));
        }

        long closedAt = chargeStart + 11 * ONE_MINUTE_MS;
        store.finishSession(sessionId, ObdLocalStore.STATUS_COMPLETE, closedAt, "");

        // Drive the materializers exactly as SessionRecorder.closeSession does.
        MaterializerInput input = new MaterializerInput(sessionId, T_BASE, closedAt);
        List<Trip> trips = TripMaterializer.materialize(input, store);
        store.persistTrips(sessionId, trips);
        List<ChargeSession> charges = ChargeSessionMaterializer.materialize(input, store);
        store.persistChargeSessions(sessionId, charges);

        assertEquals("one trip expected", 1, trips.size());
        assertEquals("one charge session expected", 1, charges.size());

        // Re-read the rows from the underlying DB. We have to reach for the helper via
        // reflection because ObdLocalStore intentionally does not expose a getter for the
        // SQLite handle — production code reads everything via typed accessors. Doing it
        // here keeps the integration test honest: we're proving the bytes actually landed.
        SQLiteDatabase db = openHelper(store);
        assertEquals(1, countRows(db, "trip_segments", sessionId));
        assertEquals(1, countRows(db, "charge_sessions", sessionId));

        try (Cursor cursor =
                db.query(
                        "trip_segments",
                        new String[] {
                            "started_at_ms",
                            "ended_at_ms",
                            "distance_m",
                            "max_speed_kph",
                            "confidence",
                            "classification",
                            "route_available"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(T_BASE, cursor.getLong(0));
            assertTrue(cursor.getDouble(2) > 100.0);
            assertEquals("OBSERVED", cursor.getString(5));
            assertEquals(1, cursor.getInt(6));
        }

        try (Cursor cursor =
                db.query(
                        "charge_sessions",
                        new String[] {
                            "started_at_ms",
                            "ended_at_ms",
                            "charger_type",
                            "voltage",
                            "confidence",
                            "interrupted"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(chargeStart, cursor.getLong(0));
            assertEquals("unknown", cursor.getString(2));
            assertEquals(14.4, cursor.getDouble(3), 0.001);
            // Voltage-only inference → WEAK → score 0.4.
            assertEquals(0.4, cursor.getDouble(4), 0.001);
            assertEquals(0, cursor.getInt(5));
        }
    }

    @Test
    public void persistMethodsAreSafeWithEmptyInput() {
        long sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE);

        store.persistTrips(sessionId, java.util.Collections.emptyList());
        store.persistChargeSessions(sessionId, java.util.Collections.emptyList());

        SQLiteDatabase db = openHelper(store);
        assertEquals(0, countRows(db, "trip_segments", sessionId));
        assertEquals(0, countRows(db, "charge_sessions", sessionId));
    }

    @Test
    public void readMethodsReturnOldestFirst() {
        long sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE);
        store.recordLocationSample(
                sessionId,
                T_BASE + 3000L,
                "gps",
                32.71,
                -117.10,
                5.0,
                null,
                null,
                null,
                null,
                null);
        store.recordLocationSample(
                sessionId,
                T_BASE + 1000L,
                "gps",
                32.70,
                -117.10,
                5.0,
                null,
                null,
                null,
                null,
                null);
        store.recordLocationSample(
                sessionId,
                T_BASE + 2000L,
                "gps",
                32.705,
                -117.10,
                5.0,
                null,
                null,
                null,
                null,
                null);

        java.util.List<com.volttracker.obdpoc.materialize.LocationSample> samples =
                store.readLocationSamples(sessionId);
        assertEquals(3, samples.size());
        assertEquals(T_BASE + 1000L, samples.get(0).capturedAtMs);
        assertEquals(T_BASE + 2000L, samples.get(1).capturedAtMs);
        assertEquals(T_BASE + 3000L, samples.get(2).capturedAtMs);
    }

    // ---- helpers ------------------------------------------------------------------

    private static JSONObject telemetry(long atMs, int speedKph, int rpm, double voltage)
            throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", rpm);
        sample.put("voltage", voltage);
        sample.put("updatedAt", atMs);
        return sample;
    }

    private static long countRows(SQLiteDatabase db, String table, long sessionId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE session_id = ?",
                        new String[] {String.valueOf(sessionId)})) {
            assertNotNull(cursor);
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private static SQLiteDatabase openHelper(ObdLocalStore store) {
        try {
            java.lang.reflect.Field helperField = ObdLocalStore.class.getDeclaredField("helper");
            helperField.setAccessible(true);
            Object helper = helperField.get(store);
            java.lang.reflect.Method getReadable =
                    helper.getClass().getMethod("getReadableDatabase");
            return (SQLiteDatabase) getReadable.invoke(helper);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not open VoltTrackerDb helper", e);
        }
    }
}
