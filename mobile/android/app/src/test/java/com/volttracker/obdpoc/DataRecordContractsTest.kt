package com.volttracker.obdpoc

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.ObdTripLabels
import com.volttracker.obdpoc.data.StatusEventRecord
import com.volttracker.obdpoc.data.TelemetrySampleRecord
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Focused tests for small data contracts that feed dashboard JSON projections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataRecordContractsTest {
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE ${VoltTrackerDb.TABLE_EVENTS} (" +
                "_id INTEGER PRIMARY KEY, " +
                "session_id INTEGER, " +
                "occurred_at_ms INTEGER, " +
                "kind TEXT, " +
                "detail TEXT, " +
                "payload TEXT" +
                ")",
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun statusEventRecordNormalizesNullStringsAndParsesPayload() {
        val withPayload =
            StatusEventRecord(
                id = 1L,
                sessionId = 2L,
                occurredAtMs = 3L,
                kind = null,
                state = null,
                detail = null,
                blocked = true,
                payload = "{\"code\":\"P0420\"}",
            )

        assertEquals("", withPayload.kind)
        assertEquals("", withPayload.state)
        assertEquals("", withPayload.detail)
        assertTrue(withPayload.blocked)
        assertEquals("P0420", withPayload.toJson().getString("code"))

        val malformed = StatusEventRecord(1L, 2L, 3L, "kind", "state", "detail", false, "not json")
        assertEquals(0, malformed.toJson().length())
    }

    @Test
    fun telemetrySampleRecordNormalizesNullStringsAndParsesJson() {
        val sample =
            TelemetrySampleRecord(
                id = 1L,
                sessionId = 2L,
                capturedAtMs = 3L,
                source = null,
                vehicleState = null,
                speedKph = 42,
                rpm = 1500,
                coolantC = 80,
                loadPct = 20,
                throttlePct = 10,
                voltage = 12.4,
                soc = 55.0,
                batteryTemp = 24.0,
                powerKw = 8.5,
                sampleNumber = 7,
                sessionMs = 900L,
                raw = null,
                json = "{\"speedKph\":42}",
            )

        assertEquals("", sample.source)
        assertEquals("", sample.vehicleState)
        assertEquals("", sample.raw)
        assertEquals(42, sample.toJson().getInt("speedKph"))

        val malformed =
            TelemetrySampleRecord(1L, 2L, 3L, "obd", "drive", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0L, "", "{")
        assertEquals(0, malformed.toJson().length())
    }

    @Test
    fun tripLabelCleaningTrimsClampsAndPreservesSurrogatePairs() {
        assertEquals("", ObdTripLabels.cleanLabel(null))
        assertEquals("Home", ObdTripLabels.cleanLabel("  Home  "))

        val long = "x".repeat(ObdTripLabels.MAX_LABEL_LEN + 8)
        assertEquals(ObdTripLabels.MAX_LABEL_LEN, ObdTripLabels.cleanLabel(long).length)

        val withSurrogateAtCut = "x".repeat(ObdTripLabels.MAX_LABEL_LEN - 1) + "\uD83D\uDE00"
        val cleaned = ObdTripLabels.cleanLabel(withSurrogateAtCut)
        assertEquals(ObdTripLabels.MAX_LABEL_LEN - 1, cleaned.length)
        assertFalse(cleaned.last().isHighSurrogate())
    }

    @Test
    fun tripLabelEventPayloadUsesCleanLabel() {
        val payload = ObdTripLabels.eventPayload("7:1000:2000", "  Commute  ")

        assertEquals("7:1000:2000", payload.getString("routeKey"))
        assertEquals("Commute", payload.getString("label"))
    }

    @Test
    fun labelsByRouteKeyUsesNewestDecisionAndEmptyLabelClears() {
        insertLabel(1, 7, 1_000, "7:1000:2000", "Morning")
        insertLabel(2, 7, 2_000, "7:1000:2000", "")
        insertLabel(3, 8, 3_000, "8:1000:2000", "Errand")
        insertLabel(4, 8, 4_000, "8:1000:2000", "Latest")
        insertLabel(5, 9, 5_000, "not-a-route-key", "Ignored")
        insertOtherKind(6, 8, 6_000, "8:1000:2000")

        val labels = ObdTripLabels.labelsByRouteKey(db, listOf(7L, 8L, 9L))

        assertFalse("newest empty label clears the old value", labels.containsKey("7:1000:2000"))
        assertEquals("Latest", labels["8:1000:2000"])
        assertFalse(labels.containsKey("not-a-route-key"))
    }

    @Test
    fun labelsByRouteKeyReturnsEmptyMapForEmptySessionListWithoutQuerying() {
        assertTrue(ObdTripLabels.labelsByRouteKey(db, emptyList()).isEmpty())
    }

    private fun insertLabel(
        id: Long,
        sessionId: Long,
        occurredAtMs: Long,
        routeKey: String,
        label: String,
    ) {
        insertEvent(id, sessionId, occurredAtMs, ObdTripLabels.EVENT_KIND, routeKey, JSONObject().put("label", label))
    }

    private fun insertOtherKind(
        id: Long,
        sessionId: Long,
        occurredAtMs: Long,
        routeKey: String,
    ) {
        insertEvent(id, sessionId, occurredAtMs, "trip_hidden", routeKey, JSONObject().put("label", "Hidden"))
    }

    private fun insertEvent(
        id: Long,
        sessionId: Long,
        occurredAtMs: Long,
        kind: String,
        routeKey: String,
        payload: JSONObject,
    ) {
        db.execSQL(
            "INSERT INTO ${VoltTrackerDb.TABLE_EVENTS} (_id, session_id, occurred_at_ms, kind, detail, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, sessionId, occurredAtMs, kind, routeKey, payload.toString()),
        )
    }
}
