package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises [DatabaseMerger] against two independent real SQLite databases: id remapping, session
 * dedup by start time, vehicle merge by key, foreign-key integrity after the merge, and
 * idempotency. Both handles run with foreign keys enabled (via [VoltTrackerDb.onConfigure]), so a
 * remap bug would surface as an insert/constraint failure here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMergerTest {
    private lateinit var liveHelper: VoltTrackerDb
    private lateinit var donorHelper: VoltTrackerDb
    private lateinit var live: SQLiteDatabase
    private lateinit var donor: SQLiteDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        liveHelper = VoltTrackerDb(context, "merge-live.db")
        donorHelper = VoltTrackerDb(context, "merge-donor.db")
        live = liveHelper.writableDatabase
        donor = donorHelper.writableDatabase
    }

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        liveHelper.close()
        donorHelper.close()
        context.deleteDatabase("merge-live.db")
        context.deleteDatabase("merge-donor.db")
    }

    @Test
    fun mergesNewSessionAndReattachesTelemetry() {
        val liveSession = insertSession(live, 1000L)
        insertTelemetry(live, liveSession, 1000L)

        val donorSession = insertSession(donor, 2000L)
        insertTelemetry(donor, donorSession, 2000L)
        insertTelemetry(donor, donorSession, 2001L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(0, result.sessionsSkipped)
        assertEquals(2, count(live, VoltTrackerDb.TABLE_SESSIONS))
        // 1 live + 2 donor telemetry rows, all with a resolvable session_id.
        assertEquals(3, count(live, VoltTrackerDb.TABLE_TELEMETRY))
        assertEquals(
            0,
            count(
                live,
                VoltTrackerDb.TABLE_TELEMETRY +
                    " t WHERE NOT EXISTS (SELECT 1 FROM " +
                    VoltTrackerDb.TABLE_SESSIONS +
                    " s WHERE s._id = t.session_id)",
            ),
        )
        assertForeignKeysIntact(live)
    }

    @Test
    fun matchesDuplicateSessionAndImportsMissingChildren() {
        val liveSession = insertSession(live, 1000L)
        insertTelemetry(live, liveSession, 1000L)

        // Donor has the same start time (a duplicate drive) plus a genuinely new one.
        val dupSession = insertSession(donor, 1000L)
        insertTelemetry(donor, dupSession, 1000L)
        insertTelemetry(donor, dupSession, 1001L)
        val newSession = insertSession(donor, 3000L)
        insertTelemetry(donor, newSession, 3000L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.sessionsSkipped)
        assertEquals(2, count(live, VoltTrackerDb.TABLE_SESSIONS))
        // Live's 1 + one missing row from the matched duplicate + the new donor session's row.
        assertEquals(3, count(live, VoltTrackerDb.TABLE_TELEMETRY))
        assertForeignKeysIntact(live)
    }

    @Test
    fun duplicateSessionImportsMissingRouteRowsForMap() {
        val liveSession = insertSession(live, 1000L)

        val duplicateDonorSession = insertSession(donor, 1000L)
        insertLocationSample(donor, duplicateDonorSession, 1000L)
        insertLocationSample(donor, duplicateDonorSession, 2000L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(0, result.sessionsAdded)
        assertEquals(1, result.sessionsSkipped)
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS))
        assertEquals(2, count(live, VoltTrackerDb.TABLE_LOCATION_SAMPLES))
        assertTrue(DatabaseMerger.merge(live, donor).ok)
        assertEquals("re-merging must not duplicate route rows", 2, count(live, VoltTrackerDb.TABLE_LOCATION_SAMPLES))
        assertEquals(2, ObdStoreRouteProjection.routePointsForSessionJson(live, liveSession, 500).length())
        assertForeignKeysIntact(live)
    }

    @Test
    fun importsSameTimestampSessionWhenAdapterDiffers() {
        val liveSession = insertSession(live, 1000L, "obd", "AA:AA:AA:AA:AA:AA")
        insertTelemetry(live, liveSession, 1000L)

        val donorSession = insertSession(donor, 1000L, "obd", "BB:BB:BB:BB:BB:BB")
        insertTelemetry(donor, donorSession, 1001L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(0, result.sessionsSkipped)
        assertEquals(2, count(live, VoltTrackerDb.TABLE_SESSIONS))
        assertEquals(2, count(live, VoltTrackerDb.TABLE_TELEMETRY))
        assertForeignKeysIntact(live)
    }

    @Test
    fun mergesVehicleByKeyAndRepointsTrip() {
        val liveVehicle = insertVehicle(live, "VH1")
        val donorVehicle = insertVehicle(donor, "VH1") // same natural key -> should merge
        val donorSession = insertSession(donor, 5000L)
        insertTripSegment(donor, donorSession, donorVehicle, 5000L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(0, result.vehiclesAdded)
        assertEquals(1, result.vehiclesMerged)
        // Still exactly one vehicle row, and the imported trip points at the live vehicle id.
        assertEquals(1, count(live, VoltTrackerDb.TABLE_VEHICLES))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TRIP_SEGMENTS))
        assertEquals(
            1,
            count(
                live,
                VoltTrackerDb.TABLE_TRIP_SEGMENTS + " WHERE vehicle_id = " + liveVehicle,
            ),
        )
        assertForeignKeysIntact(live)
    }

    @Test
    fun mergeIntoEmptyImportsEverything() {
        val donorSession = insertSession(donor, 7000L)
        insertTelemetry(donor, donorSession, 7000L)
        insertVehicle(donor, "VHX")

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.vehiclesAdded)
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_VEHICLES))
        assertForeignKeysIntact(live)
    }

    @Test
    fun mergingTwiceIsIdempotent() {
        val donorSession = insertSession(donor, 8000L)
        insertTelemetry(donor, donorSession, 8000L)

        val first = DatabaseMerger.merge(live, donor)
        val second = DatabaseMerger.merge(live, donor)

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(1, first.sessionsAdded)
        assertEquals(0, second.sessionsAdded)
        assertEquals(1, second.sessionsSkipped)
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY))
    }

    @Test
    fun summaryReadsCleanlyForMatchedDuplicates() {
        insertSession(live, 1000L)
        insertSession(donor, 1000L)
        insertSession(donor, 2000L)

        val result = DatabaseMerger.merge(live, donor)

        assertEquals("Merged backup - 1 new session (1 duplicate session matched).", result.summary())
    }

    @Test
    fun summaryUsesPluralForMultipleSessions() {
        insertSession(donor, 1000L)
        insertSession(donor, 2000L)

        val result = DatabaseMerger.merge(live, donor)

        assertEquals("Merged backup - 2 new sessions.", result.summary())
    }

    @Test
    fun summaryDoesNotReadAsNoOpForVehicleOnlyImport() {
        // A backup that contributes only a vehicle (no sessions) must not say "0 sessions" as
        // though nothing was imported.
        insertVehicle(donor, "VHONLY")

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(0, result.sessionsAdded)
        assertEquals(1, result.vehiclesAdded)
        assertEquals("Merged backup - no new sessions, 1 new vehicle.", result.summary())
    }

    @Test
    fun summaryDoesNotReadAsNoOpForAdapterOnlyImport() {
        insertAdapterHistory(donor, "AD:ONLY", 1, 100L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(0, result.sessionsAdded)
        assertEquals(0, result.vehiclesAdded)
        assertEquals("Merged backup - no new sessions, 1 other row imported.", result.summary())
    }

    @Test
    fun summaryReportsMatchedExistingVehicles() {
        val donorVehicle = insertVehicle(donor, "VH1")
        insertVehicle(live, "VH1")
        val donorSession = insertSession(donor, 5000L)
        insertTripSegment(donor, donorSession, donorVehicle, 5000L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.vehiclesMerged)
        assertEquals(
            "Merged backup - 1 new session, 1 existing vehicle matched.",
            result.summary(),
        )
    }

    @Test
    fun nullDonorHandleFailsCleanly() {
        val result = DatabaseMerger.merge(live, null)

        assertFalse(result.ok)
        assertEquals("Merge failed - database handle unavailable.", result.summary())
    }

    @Test
    fun mergesEveryChildTableAndKeepsForeignKeysIntact() {
        val vehicle = insertVehicle(donor, "VHALL")
        val session = insertSession(donor, 9000L)
        insertTelemetry(donor, session, 9000L)
        insertEvent(donor, session, 9001L)
        insertPidObservation(donor, session, 9002L)
        insertLocationSample(donor, session, 9003L)
        insertFieldCapability(donor, vehicle)
        insertTripSegment(donor, session, vehicle, 9000L)
        insertChargeSession(donor, session, vehicle, 9000L)
        val battery = insertBatterySnapshot(donor, session, vehicle, 9004L)
        insertCellSnapshot(donor, battery, 0)
        insertCellSnapshot(donor, battery, 1)
        insertExport(donor, session, vehicle, 9005L)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, count(live, VoltTrackerDb.TABLE_SESSIONS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TELEMETRY))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_EVENTS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_PID_OBSERVATIONS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_LOCATION_SAMPLES))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_FIELD_CAPABILITIES))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_TRIP_SEGMENTS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_CHARGE_SESSIONS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS))
        assertEquals(2, count(live, VoltTrackerDb.TABLE_CELL_SNAPSHOTS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_EXPORTS))
        assertForeignKeysIntact(live)
    }

    @Test
    fun cellSnapshotsOfMatchedSessionAreImportedWithTheirBatteryParent() {
        // Duplicate session by start time -> the donor parent is matched to the live session, so
        // battery/cell snapshots should import instead of silently disappearing.
        insertSession(live, 1000L)
        val dupSession = insertSession(donor, 1000L)
        val battery = insertBatterySnapshot(donor, dupSession, null, 1001L)
        insertCellSnapshot(donor, battery, 0)

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(1, count(live, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS))
        assertEquals(1, count(live, VoltTrackerDb.TABLE_CELL_SNAPSHOTS))
        assertForeignKeysIntact(live)
    }

    @Test
    fun upsertsAdapterHistoryAndDiagnosticCodesOnNaturalKeys() {
        // Same natural keys on both sides exercise the merge/update branch; donor-only rows
        // exercise the insert branch.
        insertAdapterHistory(live, "AD:1", 5, 100L)
        insertAdapterHistory(donor, "AD:1", 3, 200L) // merges into the live AD:1 row
        insertAdapterHistory(donor, "AD:2", 1, 50L) // new -> inserted

        insertDiagnosticCode(live, "MOD", "P0001", "current", 2, 100L)
        insertDiagnosticCode(donor, "MOD", "P0001", "current", 4, 300L) // merges
        insertDiagnosticCode(donor, "MOD", "P0002", "pending", 1, 80L) // new -> inserted

        val result = DatabaseMerger.merge(live, donor)

        assertTrue(result.ok)
        assertEquals(2, count(live, VoltTrackerDb.TABLE_ADAPTER_HISTORY))
        assertEquals(2, count(live, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES))
        // Counters summed on the shared keys.
        assertEquals(
            8,
            scalar(
                live,
                "SELECT connect_count FROM " +
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                    " WHERE adapter_key = 'AD:1'",
            ),
        )
        assertEquals(
            6,
            scalar(
                live,
                "SELECT seen_count FROM " +
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES +
                    " WHERE dtc = 'P0001'",
            ),
        )
        assertForeignKeysIntact(live)
    }

    @Test
    fun adapterHistoryUpsertKeepsMinFirstAndMaxLastSeen() {
        // live AD:1 seen [first=1, last=100]; donor AD:1 seen [first=1, last=200] (newer).
        insertAdapterHistoryFull(live, "AD:1", 5, firstSeenMs = 50L, lastSeenMs = 100L)
        insertAdapterHistoryFull(donor, "AD:1", 3, firstSeenMs = 10L, lastSeenMs = 200L)

        assertTrue(DatabaseMerger.merge(live, donor).ok)

        assertEquals(
            "first_seen_ms must be the earliest across both",
            10,
            scalar(
                live,
                "SELECT first_seen_ms FROM " +
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                    " WHERE adapter_key = 'AD:1'",
            ),
        )
        assertEquals(
            "last_seen_ms must be the latest across both",
            200,
            scalar(
                live,
                "SELECT last_seen_ms FROM " +
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                    " WHERE adapter_key = 'AD:1'",
            ),
        )
    }

    @Test
    fun adapterHistoryUpsertKeepsLiveSessionLinkWhenDonorSessionWasSkipped() {
        val liveSession = insertSession(live, 1000L)
        insertAdapterHistoryFull(live, "AD:1", 5, firstSeenMs = 50L, lastSeenMs = 100L, lastSessionId = liveSession)

        val duplicateDonorSession = insertSession(donor, 1000L)
        insertAdapterHistoryFull(
            donor,
            "AD:1",
            3, // first
            10L, // last
            200L,
            duplicateDonorSession,
        )

        assertTrue(DatabaseMerger.merge(live, donor).ok)

        assertEquals(
            "duplicate donor parent must not null the existing live link",
            liveSession.toInt(),
            scalar(
                live,
                "SELECT last_session_id FROM " +
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                    " WHERE adapter_key = 'AD:1'",
            ),
        )
    }

    @Test
    fun diagnosticCodeUpsertKeepsLiveSessionLinkWhenDonorSessionWasSkipped() {
        val liveSession = insertSession(live, 1000L)
        insertDiagnosticCode(live, "MOD", "P0001", "current", 2, 100L, liveSession)

        val duplicateDonorSession = insertSession(donor, 1000L)
        insertDiagnosticCode(donor, "MOD", "P0001", "current", 4, 300L, duplicateDonorSession)

        assertTrue(DatabaseMerger.merge(live, donor).ok)

        assertEquals(
            "duplicate donor parent must not null the existing live link",
            liveSession.toInt(),
            scalar(
                live,
                "SELECT last_session_id FROM " +
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES +
                    " WHERE dtc = 'P0001'",
            ),
        )
    }

    @Test
    fun remapsTripSegmentSamplePointersToTheMergedTelemetryRows() {
        // A donor trip segment references specific donor telemetry rows via start/end_sample_id.
        // After merge those pointers must resolve to the NEWLY-inserted telemetry rows (the donor
        // ids would otherwise collide with unrelated live rows), and stay foreign-key valid.
        val donorSession = insertSession(donor, 6000L)
        val startTel = insertTelemetry(donor, donorSession, 6000L) // captured_at_ms 6000
        insertTelemetry(donor, donorSession, 6500L)
        val endTel = insertTelemetry(donor, donorSession, 7000L) // captured_at_ms 7000
        insertTripSegmentWithSamples(donor, donorSession, 6000L, startTel, endTel)

        // Pre-seed the live DB with unrelated telemetry so donor ids (1..3) collide with live ids.
        val liveSession = insertSession(live, 100L)
        insertTelemetry(live, liveSession, 100L)
        insertTelemetry(live, liveSession, 200L)

        assertTrue(DatabaseMerger.merge(live, donor).ok)
        assertForeignKeysIntact(live)

        // The merged trip segment's start/end pointers must land on telemetry rows whose
        // captured_at_ms match the donor's start (6000) and end (7000) samples — proving the
        // remap tracked the right rows, not stale donor ids.
        assertEquals(
            6000,
            scalar(
                live,
                "SELECT t.captured_at_ms FROM " +
                    VoltTrackerDb.TABLE_TRIP_SEGMENTS +
                    " s JOIN " +
                    VoltTrackerDb.TABLE_TELEMETRY +
                    " t ON t._id = s.start_sample_id",
            ),
        )
        assertEquals(
            7000,
            scalar(
                live,
                "SELECT t.captured_at_ms FROM " +
                    VoltTrackerDb.TABLE_TRIP_SEGMENTS +
                    " s JOIN " +
                    VoltTrackerDb.TABLE_TELEMETRY +
                    " t ON t._id = s.end_sample_id",
            ),
        )
    }

    @Test
    fun donorColumnListsStayInSyncWithTheLiveSchema() {
        // DatabaseMerger queries donors with explicit column lists (so older backups merge
        // cleanly). The flip side: a migration that adds a column without updating the list would
        // silently drop that column's data during merges. This pins the lists to the live schema.
        val mergeTables =
            arrayOf(
                VoltTrackerDb.TABLE_VEHICLES,
                VoltTrackerDb.TABLE_SESSIONS,
                VoltTrackerDb.TABLE_TELEMETRY,
                VoltTrackerDb.TABLE_EVENTS,
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                VoltTrackerDb.TABLE_TRIP_SEGMENTS,
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                VoltTrackerDb.TABLE_CELL_SNAPSHOTS,
                VoltTrackerDb.TABLE_EXPORTS,
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
            )
        for (table in mergeTables) {
            val schemaColumns = HashSet<String>()
            live.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    schemaColumns.add(cursor.getString(nameIndex))
                }
            }
            assertEquals(
                "DatabaseMerger's donor column list for '$table' is out of sync with the live " +
                    "schema — update the list when a migration changes this table.",
                schemaColumns,
                DatabaseMerger.donorColumnsFor(table).toSet(),
            )
        }
    }

    // ---- fixtures ---------------------------------------------------------------

    companion object {
        private fun insertSession(
            db: SQLiteDatabase,
            startedAtMs: Long,
        ): Long = insertSession(db, startedAtMs, "obd", null)

        private fun insertSession(
            db: SQLiteDatabase,
            startedAtMs: Long,
            mode: String,
            adapterAddress: String?,
        ): Long {
            val cv = ContentValues()
            cv.put("mode", mode)
            if (adapterAddress != null) {
                cv.put("adapter_address", adapterAddress)
            }
            cv.put("started_at_ms", startedAtMs)
            cv.put("status", "complete")
            cv.put("sample_count", 0)
            cv.put("created_at_ms", startedAtMs)
            return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv)
        }

        private fun insertTelemetry(
            db: SQLiteDatabase,
            sessionId: Long,
            capturedAtMs: Long,
        ): Long {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("captured_at_ms", capturedAtMs)
            cv.put("speed_kph", 40)
            cv.put("json", "{}")
            return db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, cv)
        }

        private fun insertTripSegmentWithSamples(
            db: SQLiteDatabase,
            sessionId: Long,
            startedAtMs: Long,
            startSampleId: Long,
            endSampleId: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("started_at_ms", startedAtMs)
            cv.put("route_available", 1)
            cv.put("start_sample_id", startSampleId)
            cv.put("end_sample_id", endSampleId)
            cv.put("created_at_ms", startedAtMs)
            db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, cv)
        }

        private fun insertVehicle(
            db: SQLiteDatabase,
            key: String,
        ): Long {
            val cv = ContentValues()
            cv.put("vehicle_key", key)
            cv.put("display_name", "Test $key")
            cv.put("first_seen_ms", 1L)
            cv.put("last_seen_ms", 2L)
            cv.put("created_at_ms", 1L)
            cv.put("updated_at_ms", 2L)
            return db.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv)
        }

        private fun insertTripSegment(
            db: SQLiteDatabase,
            sessionId: Long,
            vehicleId: Long,
            startedAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("vehicle_id", vehicleId)
            cv.put("started_at_ms", startedAtMs)
            cv.put("route_available", 0)
            cv.put("created_at_ms", startedAtMs)
            db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, cv)
        }

        private fun insertEvent(
            db: SQLiteDatabase,
            sessionId: Long,
            occurredAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("occurred_at_ms", occurredAtMs)
            cv.put("kind", "status")
            cv.put("blocked", 0)
            cv.put("payload", "{}")
            db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, cv)
        }

        private fun insertPidObservation(
            db: SQLiteDatabase,
            sessionId: Long,
            observedAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("observed_at_ms", observedAtMs)
            cv.put("pid", "0105")
            cv.put("json", "{}")
            db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, cv)
        }

        private fun insertLocationSample(
            db: SQLiteDatabase,
            sessionId: Long,
            capturedAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("captured_at_ms", capturedAtMs)
            cv.put("latitude", 37.0)
            cv.put("longitude", -122.0)
            cv.put("json", "{}")
            db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, cv)
        }

        private fun insertFieldCapability(
            db: SQLiteDatabase,
            vehicleId: Long,
        ) {
            val cv = ContentValues()
            cv.put("vehicle_id", vehicleId)
            cv.put("command", "0105")
            cv.put("supported", 1)
            cv.put("response_count", 1)
            cv.put("first_seen_ms", 1L)
            cv.put("last_seen_ms", 2L)
            db.insertOrThrow(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, cv)
        }

        private fun insertChargeSession(
            db: SQLiteDatabase,
            sessionId: Long,
            vehicleId: Long,
            startedAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("vehicle_id", vehicleId)
            cv.put("started_at_ms", startedAtMs)
            cv.put("interrupted", 0)
            cv.put("created_at_ms", startedAtMs)
            db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, cv)
        }

        private fun insertBatterySnapshot(
            db: SQLiteDatabase,
            sessionId: Long,
            vehicleId: Long?,
            capturedAtMs: Long,
        ): Long {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            if (vehicleId != null) {
                cv.put("vehicle_id", vehicleId)
            }
            cv.put("captured_at_ms", capturedAtMs)
            cv.put("created_at_ms", capturedAtMs)
            return db.insertOrThrow(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, cv)
        }

        private fun insertCellSnapshot(
            db: SQLiteDatabase,
            batterySnapshotId: Long,
            index: Int,
        ) {
            val cv = ContentValues()
            cv.put("battery_snapshot_id", batterySnapshotId)
            cv.put("cell_index", index)
            cv.put("voltage", 3.7)
            db.insertOrThrow(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, cv)
        }

        private fun insertExport(
            db: SQLiteDatabase,
            sessionId: Long,
            vehicleId: Long,
            createdAtMs: Long,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("vehicle_id", vehicleId)
            cv.put("created_at_ms", createdAtMs)
            cv.put("export_type", "csv")
            cv.put("status", "complete")
            db.insertOrThrow(VoltTrackerDb.TABLE_EXPORTS, null, cv)
        }

        private fun insertAdapterHistory(
            db: SQLiteDatabase,
            key: String,
            connectCount: Int,
            lastSeenMs: Long,
        ) {
            insertAdapterHistoryFull(db, key, connectCount, 1L, lastSeenMs)
        }

        private fun insertAdapterHistoryFull(
            db: SQLiteDatabase,
            key: String,
            connectCount: Int,
            firstSeenMs: Long,
            lastSeenMs: Long,
        ) {
            insertAdapterHistoryFull(db, key, connectCount, firstSeenMs, lastSeenMs, null)
        }

        private fun insertAdapterHistoryFull(
            db: SQLiteDatabase,
            key: String,
            connectCount: Int,
            firstSeenMs: Long,
            lastSeenMs: Long,
            lastSessionId: Long?,
        ) {
            val cv = ContentValues()
            cv.put("adapter_key", key)
            cv.put("connect_count", connectCount)
            cv.put("scan_count", 0)
            cv.put("demo_count", 0)
            cv.put("sample_count", 0)
            cv.put("first_seen_ms", firstSeenMs)
            cv.put("last_seen_ms", lastSeenMs)
            if (lastSessionId != null) {
                cv.put("last_session_id", lastSessionId)
            }
            db.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv)
        }

        private fun insertDiagnosticCode(
            db: SQLiteDatabase,
            moduleKey: String,
            dtc: String,
            status: String,
            seenCount: Int,
            lastSeenMs: Long,
        ) {
            insertDiagnosticCode(db, moduleKey, dtc, status, seenCount, lastSeenMs, null)
        }

        private fun insertDiagnosticCode(
            db: SQLiteDatabase,
            moduleKey: String,
            dtc: String,
            status: String,
            seenCount: Int,
            lastSeenMs: Long,
            lastSessionId: Long?,
        ) {
            val cv = ContentValues()
            cv.put("module_key", moduleKey)
            cv.put("dtc", dtc)
            cv.put("status", status)
            cv.put("seen_count", seenCount)
            cv.put("first_seen_ms", 1L)
            cv.put("last_seen_ms", lastSeenMs)
            cv.put("json", "{}")
            if (lastSessionId != null) {
                cv.put("last_session_id", lastSessionId)
            }
            db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv)
        }

        private fun count(
            db: SQLiteDatabase,
            tableAndMaybeWhere: String,
        ): Int =
            db.rawQuery("SELECT COUNT(*) FROM $tableAndMaybeWhere", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }

        private fun scalar(
            db: SQLiteDatabase,
            sql: String,
        ): Int =
            db.rawQuery(sql, null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }

        private fun assertForeignKeysIntact(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                assertFalse("foreign_key_check reported violations after merge", c.moveToFirst())
            }
        }
    }
}
