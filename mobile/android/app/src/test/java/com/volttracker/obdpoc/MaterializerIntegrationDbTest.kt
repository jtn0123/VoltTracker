package com.volttracker.obdpoc

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.materialize.ChargeSession
import com.volttracker.obdpoc.materialize.ChargeSessionMaterializer
import com.volttracker.obdpoc.materialize.Confidence
import com.volttracker.obdpoc.materialize.MaterializerInput
import com.volttracker.obdpoc.materialize.Trip
import com.volttracker.obdpoc.materialize.TripMaterializer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration test for the materializer round-trip: seed a real [ObdLocalStore] with one session's
 * worth of telemetry and location samples, run the materializers, persist their output, and confirm
 * the `trip_segments` / `charge_sessions` rows actually landed.
 *
 * The "flag off" half of the original spec is documented but not exercised: `BuildFlags`
 * `MATERIALIZE_SESSIONS` is a `static final boolean`, so its branch is dead code at compile time and
 * a test that toggled it would prove nothing about the production binary. The intent of the flag is
 * to give us a one-line revert if the heuristic misfires in the field; that is verified by
 * inspecting the call site, not by a runtime test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterializerIntegrationDbTest {
    private var store: ObdLocalStore? = null

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store!!.clearAllData()
    }

    @After
    fun tearDown() {
        store?.close()
    }

    @Test
    fun materializersWriteTripAndChargeRowsForASeededSession() {
        val store = this.store!!
        val sessionId = store.startSession("obd", "AA:BB:CC", "Adapter", T_BASE)

        // Seed a driving window: 12 location samples (>= OBSERVED threshold) over ~5.5 minutes.
        for (i in 0 until 12) {
            store.recordLocationSample(
                sessionId,
                T_BASE + i * 30 * 1_000L,
                "gps",
                34.050000 + i * 0.0015,
                -118.250000,
                5.0,
                null,
                null,
                null,
                null,
                null,
            )
        }
        // Telemetry rows during the drive — voltage low. Speed picked to match the GPS-derived
        // value (5 m/s ≈ 18 kph above) so the classification driver (avg distance/duration off
        // the location samples) and the OBD speedKph column tell the same story; the classifier
        // does not read OBD speed, but matching values keeps future maintainers from second-
        // guessing which source drives the assertion below.
        for (i in 0 until 12) {
            store.recordTelemetry(sessionId, telemetry(T_BASE + i * 30 * 1_000L, 20, 1500, 12.3))
        }

        // Then a charge window: 10 minutes of high-voltage stationary telemetry.
        val chargeStart = T_BASE + 10 * ONE_MINUTE_MS
        for (i in 0..10) {
            store.recordTelemetry(sessionId, telemetry(chargeStart + i * ONE_MINUTE_MS, 0, 0, 14.4))
        }

        val closedAt = chargeStart + 11 * ONE_MINUTE_MS
        store.finishSession(sessionId, ObdLocalStore.STATUS_COMPLETE, closedAt, "")

        // Drive the materializers exactly as SessionRecorder.closeSession does.
        val input = MaterializerInput(sessionId, T_BASE, closedAt)
        val trips = TripMaterializer.materialize(input, store)
        store.persistTrips(sessionId, trips)
        val charges = ChargeSessionMaterializer.materialize(input, store)
        store.persistChargeSessions(sessionId, charges)

        assertEquals("one trip expected", 1, trips.size)
        assertEquals("one charge session expected", 1, charges.size)

        // Re-read the rows from the underlying DB. We have to reach for the helper via
        // reflection because ObdLocalStore intentionally does not expose a getter for the
        // SQLite handle — production code reads everything via typed accessors. Doing it
        // here keeps the integration test honest: we're proving the bytes actually landed.
        val db = openHelper(store)
        assertEquals(1, countRows(db, "trip_segments", sessionId))
        assertEquals(1, countRows(db, "charge_sessions", sessionId))

        db
            .query(
                "trip_segments",
                arrayOf(
                    "started_at_ms",
                    "ended_at_ms",
                    "distance_m",
                    "max_speed_kph",
                    "confidence",
                    "classification",
                    "route_available",
                ),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(T_BASE, cursor.getLong(0))
                assertTrue(cursor.getDouble(2) > 100.0)
                // confidence (col 4) is the score of the materializer's certainty (OBSERVED → 0.8);
                // classification (col 5) is the driving-regime label. The seed window covers
                // ≈1.8 km over 5.5 min ⇒ avg ≈ 20 kph, which buckets to "city".
                assertEquals(0.8, cursor.getDouble(4), 0.001)
                assertEquals(Trip.CLASSIFICATION_CITY, cursor.getString(5))
                assertEquals(1, cursor.getInt(6))
            }

        db
            .query(
                "charge_sessions",
                arrayOf(
                    "started_at_ms",
                    "ended_at_ms",
                    "charger_type",
                    "voltage",
                    "confidence",
                    "interrupted",
                ),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(chargeStart, cursor.getLong(0))
                assertEquals("unknown", cursor.getString(2))
                assertEquals(14.4, cursor.getDouble(3), 0.001)
                // Voltage-only inference → WEAK → score 0.4.
                assertEquals(0.4, cursor.getDouble(4), 0.001)
                assertEquals(0, cursor.getInt(5))
            }
    }

    @Test
    fun persistMethodsAreSafeWithEmptyInput() {
        val store = this.store!!
        val sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE)

        store.persistTrips(sessionId, emptyList())
        store.persistChargeSessions(sessionId, emptyList())

        val db = openHelper(store)
        assertEquals(0, countRows(db, "trip_segments", sessionId))
        assertEquals(0, countRows(db, "charge_sessions", sessionId))
    }

    @Test
    fun readMethodsReturnOldestFirst() {
        val store = this.store!!
        val sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE)
        store.recordLocationSample(
            sessionId,
            T_BASE + 3000L,
            "gps",
            34.06,
            -118.25,
            5.0,
            null,
            null,
            null,
            null,
            null,
        )
        store.recordLocationSample(
            sessionId,
            T_BASE + 1000L,
            "gps",
            34.05,
            -118.25,
            5.0,
            null,
            null,
            null,
            null,
            null,
        )
        store.recordLocationSample(
            sessionId,
            T_BASE + 2000L,
            "gps",
            34.055,
            -118.25,
            5.0,
            null,
            null,
            null,
            null,
            null,
        )

        val samples = store.readLocationSamples(sessionId)
        assertEquals(3, samples.size)
        assertEquals(T_BASE + 1000L, samples[0].capturedAtMs)
        assertEquals(T_BASE + 2000L, samples[1].capturedAtMs)
        assertEquals(T_BASE + 3000L, samples[2].capturedAtMs)
    }

    @Test
    fun readPidObservationsReturnOldestFirstWithNullableNumericValues() {
        val store = this.store!!
        val sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE)

        store.recordPidObservation(
            sessionId,
            T_BASE + 3000L,
            "01 0C",
            "7E8",
            "0C",
            "Engine RPM",
            "1500 rpm",
            1500.0,
            "rpm",
            "01 0C",
            "41 0C 2E E0",
        )
        store.recordPidObservation(
            sessionId,
            T_BASE + 1000L,
            "01 05",
            "7E8",
            "05",
            "Coolant",
            "no parse",
            null,
            "C",
            "01 05",
            "NO DATA",
        )
        store.recordPidObservation(
            sessionId,
            T_BASE + 2000L,
            "22 2414",
            "7E4",
            "2414",
            "Pack current",
            "-12.5 A",
            -12.5,
            "A",
            "22 2414",
            "62 24 14 FF 83",
        )

        val observations = store.readPidObservations(sessionId)

        assertEquals(3, observations.size)
        assertEquals(T_BASE + 1000L, observations[0].capturedAtMs)
        assertEquals("01 05", observations[0].command)
        assertEquals("7E8", observations[0].header)
        assertEquals("NO DATA", observations[0].response)
        assertEquals(null, observations[0].parserKey)
        assertEquals(null, observations[0].parsedNumeric)
        assertEquals(T_BASE + 2000L, observations[1].capturedAtMs)
        assertEquals(-12.5, observations[1].parsedNumeric!!, 0.001)
        assertEquals(T_BASE + 3000L, observations[2].capturedAtMs)
        assertEquals(1500.0, observations[2].parsedNumeric!!, 0.001)
    }

    @Test
    fun persistMethodsWriteOptionalEnergySocAndPowerColumns() {
        val store = this.store!!
        val sessionId = store.startSession("obd", "AA:BB", "Adapter", T_BASE)

        store.persistTrips(
            sessionId,
            listOf(
                Trip(
                    T_BASE,
                    T_BASE,
                    0.0,
                    0L,
                    0.0,
                    1,
                    false,
                    Confidence.WEAK,
                    Trip.CLASSIFICATION_UNKNOWN,
                    1.75,
                ),
            ),
        )
        store.persistChargeSessions(
            sessionId,
            listOf(
                ChargeSession(
                    T_BASE + ONE_MINUTE_MS,
                    T_BASE + 11 * ONE_MINUTE_MS,
                    10 * ONE_MINUTE_MS,
                    355.5,
                    361.0,
                    2,
                    "level2",
                    Confidence.OBSERVED,
                    startSoc = 44.0,
                    endSoc = 66.5,
                    peakPowerKw = 6.8,
                    energyKwh = 3.4,
                ),
            ),
        )

        val db = openHelper(store)
        db
            .query(
                "trip_segments",
                arrayOf("route_available", "avg_speed_kph", "energy_kwh", "confidence"),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertTrue("zero-duration trips must not persist an average speed", cursor.isNull(1))
                assertEquals(1.75, cursor.getDouble(2), 0.001)
                assertEquals(0.4, cursor.getDouble(3), 0.001)
            }
        db
            .query(
                "charge_sessions",
                arrayOf(
                    "charger_type",
                    "start_soc",
                    "end_soc",
                    "power_kw",
                    "energy_kwh",
                    "interrupted",
                    "summary_json",
                ),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("level2", cursor.getString(0))
                assertEquals(44.0, cursor.getDouble(1), 0.001)
                assertEquals(66.5, cursor.getDouble(2), 0.001)
                assertEquals(6.8, cursor.getDouble(3), 0.001)
                assertEquals(3.4, cursor.getDouble(4), 0.001)
                assertEquals(1, cursor.getInt(5))
                val summary = JSONObject(cursor.getString(6))
                assertEquals(2, summary.getInt("interruptionCount"))
                assertEquals("OBSERVED", summary.getString("confidence"))
                assertEquals(355.5, summary.getDouble("voltageStart"), 0.001)
                assertEquals(361.0, summary.getDouble("voltageEnd"), 0.001)
            }
    }

    // ---- helpers ------------------------------------------------------------------

    companion object {
        private const val T_BASE = 1_700_000_000_000L
        private const val ONE_MINUTE_MS = 60_000L

        private fun telemetry(
            atMs: Long,
            speedKph: Int,
            rpm: Int,
            voltage: Double,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", speedKph)
            sample.put("rpm", rpm)
            sample.put("voltage", voltage)
            sample.put("updatedAt", atMs)
            return sample
        }

        private fun countRows(
            db: SQLiteDatabase,
            table: String,
            sessionId: Long,
        ): Long {
            db
                .rawQuery(
                    "SELECT COUNT(*) FROM $table WHERE session_id = ?",
                    arrayOf(sessionId.toString()),
                ).use { cursor ->
                    assertNotNull(cursor)
                    cursor.moveToFirst()
                    return cursor.getLong(0)
                }
        }

        private fun openHelper(store: ObdLocalStore): SQLiteDatabase {
            try {
                val helperField = ObdLocalStore::class.java.getDeclaredField("helper")
                helperField.isAccessible = true
                val helper = helperField.get(store)
                val getReadable = helper.javaClass.getMethod("getReadableDatabase")
                return getReadable.invoke(helper) as SQLiteDatabase
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("could not open VoltTrackerDb helper", e)
            }
        }
    }
}
