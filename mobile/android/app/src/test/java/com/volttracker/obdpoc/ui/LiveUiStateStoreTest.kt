package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeSession
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.trips.TripPoint
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.TripSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUiStateStoreTest {
    private fun sample(
        updatedAt: Long = 1_000L,
        speedKph: Int = 64,
        block: JSONObject.() -> Unit = {},
    ): JSONObject =
        JSONObject()
            .put("updatedAt", updatedAt)
            .put("speedKph", speedKph)
            .put("powerKw", 21.4)
            .put("soc", 62)
            .put("packVoltage", 364.0)
            .put("packCurrentA", 58.8)
            .put("batteryTemp", 23)
            .put("coolantC", 79)
            .put("controlModuleVoltage", 14.2)
            .put("outsideTempC", 20)
            .put("transmissionTempC", 61)
            .put("engineTorqueNm", 118)
            .put("engineOilLifePct", 87)
            .put("prndlState", "D")
            .put("motorAPowerKw", 14.2)
            .put("motorBPowerKw", 3.1)
            .put("accuracyM", 4.0)
            .put("vehicleState", "driving_ev")
            .apply(block)

    @Test
    fun anUnseenGearCodeShowsAsUnknownWithItsNumber() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample { put("prndlState", "?").put("prndlRaw", 13).put("gearConfidence", "unknown") })
        assertEquals("? (code 13)", store.state.value.drive.gear)
    }

    @Test
    fun telemetrySampleMapsUnitsIntoDriveState() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample())
        val drive = store.state.value.drive

        assertEquals(39, drive.speedMph) // 64 kph ≈ 39.8 mph, truncated
        assertEquals(21.4, drive.powerKw, 1e-9)
        assertEquals(62.0, drive.socPercent, 1e-9)
        assertEquals(364.0, drive.packVolts, 1e-9)
        assertEquals(58.8, drive.packAmps, 1e-9)
        assertEquals(73, drive.packTempF) // 23 C
        assertEquals(174, drive.coolantF) // 79 C
        assertEquals(68, drive.ambientF) // 20 C
        assertEquals(141, drive.transTempF) // 61 C
        assertEquals("D", drive.gear)
        assertEquals(14.2, drive.motorAKw, 1e-9)
        assertEquals(3.1, drive.motorBKw, 1e-9)
        assertEquals(118, drive.torqueNm)
        assertEquals(87, drive.oilLifePct)
        assertEquals(13, drive.gpsAccuracyFt ?: -1) // 4 m ≈ 13.1 ft
        assertEquals(DriveMode.EV, drive.mode)
    }

    @Test
    fun evRangeComesFromTheCarsEstimateNotThisCycleDistance() {
        val store = LiveUiStateStore()
        // Only this-cycle distance reported: must NOT be presented as range.
        store.onTelemetry(sample { put("evDistanceThisCycleKm", 12.5) })
        assertNull(store.state.value.drive.evRangeMiles)

        // The car's own estimate (2241A6) is the range.
        store.onTelemetry(
            sample { put("evDistanceThisCycleKm", 12.5).put("evRangeKm", 42).put("evRangeStaleMs", 1_000) },
        )
        assertEquals(26.1, store.state.value.drive.evRangeMiles ?: Double.NaN, 0.05) // 42 km

        // A stale estimate is hidden rather than shown as current.
        store.onTelemetry(sample { put("evRangeKm", 42).put("evRangeStaleMs", 180_000) })
        assertNull(store.state.value.drive.evRangeMiles)
    }

    @Test
    fun chargeHeroMirrorsLivePackSocAndEvRange() {
        val store = LiveUiStateStore()
        // Before any range estimate the caption stays hidden rather than reading "0 mi range".
        assertNull(store.state.value.charge.evRangeMiles)

        store.onTelemetry(sample { put("evRangeKm", 42).put("evRangeStaleMs", 1_000) })
        assertEquals(62.0, store.state.value.charge.socPercent, 1e-9)
        assertEquals(26.1, store.state.value.charge.evRangeMiles ?: Double.NaN, 0.05) // 42 km

        store.onTelemetry(sample { put("evRangeKm", 42).put("evRangeStaleMs", 180_000) })
        assertNull(store.state.value.charge.evRangeMiles)

        store.onTelemetryBackfill(listOf(sample { put("soc", 55).put("evRangeKm", 30) }))
        assertEquals(55.0, store.state.value.charge.socPercent, 1e-9)
        assertEquals(18.6, store.state.value.charge.evRangeMiles ?: Double.NaN, 0.05) // 30 km
    }

    @Test
    fun missingFieldsKeepPriorValuesInsteadOfZeroing() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample())
        store.onTelemetry(JSONObject().put("updatedAt", 2_000L).put("speedKph", 32))
        val drive = store.state.value.drive

        assertEquals(19, drive.speedMph) // fresh
        assertEquals(364.0, drive.packVolts, 1e-9) // retained
        // The gear is the one tile that is never carried forward: no reading means no gear.
        assertEquals("--", drive.gear)
    }

    @Test
    fun aStaleGearReadingClearsTheGearTile() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample { put("prndlStateStaleMs", 40_000L) })
        assertEquals("D", store.state.value.drive.gear) // one missed ~37 s poll is still fresh

        store.onTelemetry(sample { put("prndlState", "P").put("prndlStateStaleMs", 180_000L) })
        assertEquals("--", store.state.value.drive.gear)
    }

    @Test
    fun aTentativeGearDecodeShowsItsLetter() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample { put("prndlState", "R").put("prndlRaw", 7).put("gearConfidence", "tentative") })
        assertEquals("R", store.state.value.drive.gear)
    }

    @Test
    fun gasVehicleStateFlipsMode() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample { put("vehicleState", "driving_gas").put("rpm", 2200) })
        assertEquals(DriveMode.GAS, store.state.value.drive.mode)
    }

    @Test
    fun tracesAccumulateAndStayBounded() {
        val store = LiveUiStateStore()
        repeat(70) { i -> store.onTelemetry(sample(updatedAt = 1_000L + i)) }
        val drive = store.state.value.drive

        assertEquals(30, drive.speedTrace.size)
        // The cockpit power strip spans the last minute at 1 Hz, with an engine flag per sample.
        assertEquals(60, drive.powerTrace.size)
        assertEquals(60, drive.gasTrace.size)
        // SOC is throttled to one sample per 30 s: 70 samples 1 ms apart → one point.
        assertEquals(1, drive.socTrace.size)
    }

    @Test
    fun socTraceSamplesEveryThirtySeconds() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(updatedAt = 0L) { put("soc", 71) })
        store.onTelemetry(sample(updatedAt = 30_000L) { put("soc", 70) })
        store.onTelemetry(sample(updatedAt = 45_000L) { put("soc", 70) }) // skipped
        store.onTelemetry(sample(updatedAt = 60_000L) { put("soc", 69) })

        assertEquals(listOf(71f, 70f, 69f), store.state.value.drive.socTrace)
    }

    @Test
    fun backfillRebuildsTracesAndLandsOnNewestSample() {
        val store = LiveUiStateStore()
        store.onTelemetryBackfill(
            listOf(
                sample(updatedAt = 1_000L, speedKph = 32),
                sample(updatedAt = 2_000L, speedKph = 48),
                sample(updatedAt = 3_000L, speedKph = 64),
            ),
        )
        val drive = store.state.value.drive

        assertEquals(3, drive.speedTrace.size)
        assertEquals(39, drive.speedMph)
    }

    @Test
    fun backfillReplacesRatherThanDuplicatesTraces() {
        val store = LiveUiStateStore()
        val batch =
            listOf(
                sample(updatedAt = 1_000L, speedKph = 32),
                sample(updatedAt = 2_000L, speedKph = 48),
                sample(updatedAt = 3_000L, speedKph = 64),
            )
        store.onTelemetryBackfill(batch)
        val first = store.state.value.drive
        // A resume replays the same service snapshot again — history must not double.
        store.onTelemetryBackfill(batch)
        val second = store.state.value.drive

        assertEquals(first.speedTrace, second.speedTrace)
        assertEquals(first.powerTrace, second.powerTrace)
        assertEquals(first.socTrace, second.socTrace)
        assertEquals(3, second.speedTrace.size)
    }

    @Test
    fun connectingStateSetsTheHandshakeFlag() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connecting").put("adapter", "OBDLink MX+"))
        assertTrue(store.state.value.drive.connecting)

        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        assertFalse(store.state.value.drive.connecting)
        assertTrue(store.state.value.drive.connected)
    }

    @Test
    fun demoStatusMarksSettingsDemoActiveUntilTheSessionEnds() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "demo"))
        assertTrue(store.state.value.settings.demoActive)

        // Once running, the demo reports "connected"; its source-tagged samples keep the flag.
        store.onStatus(JSONObject().put("state", "connected").put("adapter", "Demo stream"))
        store.onTelemetry(JSONObject().put("source", "demo").put("speedKph", 40.0))
        assertTrue(store.state.value.settings.demoActive)

        store.onStatus(JSONObject().put("state", "disconnected"))
        assertFalse(store.state.value.settings.demoActive)
    }

    @Test
    fun realSamplesClearTheDemoFlag() {
        val store = LiveUiStateStore()
        store.onTelemetry(JSONObject().put("source", "demo"))
        assertTrue(store.state.value.settings.demoActive)
        store.onTelemetry(JSONObject().put("source", "obd").put("speedKph", 12.0))
        assertFalse(store.state.value.settings.demoActive)
    }

    @Test
    fun updateStateMutatorTouchesOnlyItsSettingsFields() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))

        store.onUpdateState("v0.36.0 is available", "v0.36.0", 43)
        val settings = store.state.value.settings

        assertEquals("v0.36.0 is available", settings.updateStatusLabel)
        assertEquals("v0.36.0", settings.updateAvailableTag)
        assertEquals(43, settings.updateDownloadPercent ?: -1)
        // Unrelated settings state is preserved.
        assertTrue(settings.connected)
        assertEquals("OBDLink MX+", settings.adapterLabel)

        store.onUpdateState(null, null, null)
        val cleared = store.state.value.settings
        assertEquals(null, cleared.updateStatusLabel)
        assertEquals(null, cleared.updateAvailableTag)
        assertEquals(null, cleared.updateDownloadPercent)
    }

    @Test
    fun versionLabelMutatorTouchesOnlyTheVersionLine() {
        val store = LiveUiStateStore()
        store.onUpdateState("Up to date", null, null)

        store.onVersionLabel("Volt Tracker 0.36.0-abc1234")

        assertEquals("Volt Tracker 0.36.0-abc1234", store.state.value.settings.versionLabel)
        assertEquals("Up to date", store.state.value.settings.updateStatusLabel)
    }

    @Test
    fun statusUpdatesConnectionAcrossAllScreens() {
        val store = LiveUiStateStore()
        store.onStatus(
            JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"),
        )
        val s = store.state.value

        assertTrue(s.drive.connected)
        assertTrue(s.charge.connected)
        assertTrue(s.trips.connected)
        assertTrue(s.insights.connected)
        assertTrue(s.diag.connected)
        assertTrue(s.settings.connected)
        assertEquals("OBDLink MX+", s.diag.adapterLabel)
        assertEquals("Live · 1 Hz", s.drive.statusLabel)
    }

    @Test
    fun statusLabelsCoverScanningDemoAndIdleStates() {
        val store = LiveUiStateStore()

        store.onStatus(JSONObject().put("state", "scanning").put("adapter", "OBDLink MX+"))
        assertEquals("Scanning…", store.state.value.drive.statusLabel)
        assertTrue(store.state.value.drive.connecting)

        store.onStatus(JSONObject().put("state", "demo"))
        assertEquals("Demo", store.state.value.drive.statusLabel)
        assertTrue(store.state.value.drive.connected)

        store.onStatus(JSONObject().put("state", "idle").put("adapter", "OBDLink MX+"))
        assertEquals("Idle · OBDLink MX+", store.state.value.drive.statusLabel)

        store.onStatus(JSONObject().put("state", "idle"))
        assertEquals("No adapter", store.state.value.drive.statusLabel)
    }

    @Test
    fun rpmFloorFlipsGasModeWhenVehicleStateIsMissing() {
        val store = LiveUiStateStore()
        store.onTelemetry(
            JSONObject().put("updatedAt", 1_000L).put("rpm", 1800),
        )
        assertEquals(DriveMode.GAS, store.state.value.drive.mode)

        // Low rpm with no vehicleState keeps the prior mode rather than guessing.
        store.onTelemetry(JSONObject().put("updatedAt", 2_000L).put("rpm", 0))
        assertEquals(DriveMode.GAS, store.state.value.drive.mode)
    }

    @Test
    fun nullAndNonNumericFieldsRetainPriorValues() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample())
        store.onTelemetry(
            JSONObject()
                .put("updatedAt", 2_000L)
                .put("soc", JSONObject.NULL)
                .put("packVoltage", "not-a-number")
                // No controlModuleVoltage: auxVolts falls back to base "voltage".
                .put("voltage", 12.6),
        )
        val drive = store.state.value.drive

        assertEquals(62.0, drive.socPercent, 1e-9) // JSON null → retained
        assertEquals(364.0, drive.packVolts, 1e-9) // NaN → retained
        assertEquals(12.6, drive.auxVolts, 1e-9) // fallback key used
    }

    @Test
    fun emptyBackfillLeavesExistingTracesAlone() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample())
        store.onTelemetryBackfill(emptyList())

        assertEquals(1, store.state.value.drive.speedTrace.size)
    }

    @Test
    fun connectingStateIsNotConnected() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connecting").put("adapter", "OBDLink MX+"))
        val s = store.state.value

        assertFalse(s.drive.connected)
        assertEquals("Connecting…", s.drive.statusLabel)
    }

    @Test
    fun chargingSamplesFeedTheChargeTab() {
        val store = LiveUiStateStore()
        val plugged: JSONObject.() -> Unit = {
            put("vehicleState", "charging")
            put("speedKph", 0)
            put("chargerPowerKw", 3.6)
            put("chargerAcVoltage", 240)
            put("chargerAcCurrentA", 15)
            put("sohPct", 91)
        }
        store.onTelemetry(sample(updatedAt = 10_000L, block = plugged))
        store.onTelemetry(
            sample(updatedAt = 11_000L) {
                plugged()
                put("soc", 63)
            },
        )
        val charge = store.state.value.charge
        assertTrue(charge.charging)
        assertEquals(3.6, charge.chargeKw, 1e-9)
        assertEquals(240.0, charge.acVolts ?: Double.NaN, 1e-9)
        assertEquals(15.0, charge.acAmps ?: Double.NaN, 1e-9)
        assertEquals("L2", charge.level)
        assertEquals(62.0, charge.fromSoc ?: Double.NaN, 1e-9)
        assertEquals(10_000L, charge.startedAtMs)
        assertEquals(11_000L, charge.sampleAtMs)
        assertEquals(91.0, charge.sohPct ?: Double.NaN, 1e-9)
        assertEquals(73, charge.packTempF)
        assertEquals(listOf(62f), charge.socPoints.map { it.soc })

        store.onTelemetry(sample(updatedAt = 12_000L))
        assertFalse(store.state.value.charge.charging)
        assertTrue(
            store.state.value.charge.socPoints
                .isEmpty(),
        )
    }

    @Test
    fun settingsCarryTheRatesAndChargeLimitToTheChargeTab() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(homeRate = 0.15, publicRate = 0.45, chargeTargetPct = 80) }
        val charge = store.state.value.charge
        assertEquals(0.15, charge.homeRate, 1e-9)
        assertEquals(0.45, charge.publicRate, 1e-9)
        assertEquals(80, charge.targetSoc)
    }

    @Test
    fun loggedChargesShowUnlessTheDemoIsRunning() {
        val store = LiveUiStateStore(nowMs = { 5_000_000_000L })
        val logged = listOf(ChargeSession(1_000L, 2_000L, "L2", 40, 80, 5.6))
        store.onChargeHistory(logged)
        assertEquals(logged, store.state.value.charge.sessions)

        store.onTelemetry(sample { put("source", "demo") })
        val demo = store.state.value.charge.sessions
        assertEquals(ChargeUiState.demoSessions(5_000_000_000L), demo)
        // A later read while the demo runs is kept for after it stops, not shown now.
        store.onChargeHistory(emptyList())
        assertEquals(demo, store.state.value.charge.sessions)

        store.onStatus(JSONObject().put("state", "disconnected"))
        assertTrue(
            store.state.value.charge.sessions
                .isEmpty(),
        )
    }

    @Test
    fun loggedDrivesShowWithTheSelectedRouteAndAskForTheNextOne() {
        val store = LiveUiStateStore(nowMs = { 5_000_000_000L })
        val older = TripSummary("1:10:20", 10L, 20L, 1_000.0)
        val newer = TripSummary("1:30:40", 30L, 40L, 2_000.0)
        store.onTripHistory(listOf(newer, older))
        val trips = store.state.value.trips
        assertEquals(listOf(newer, older), trips.trips)
        assertEquals(5_000_000_000L, trips.nowMs)
        assertTrue(trips.exportable)
        // The newest drive shows first; its route is the one to read.
        assertEquals("1:30:40", store.tripRouteToRead())

        store.onTripRoute(TripRoute("1:30:40", listOf(TripPoint(0.0, 0.0, 30L))))
        assertNull(store.tripRouteToRead())
        assertEquals(
            "1:30:40",
            store.state.value.trips.selectedRoute
                ?.routeKey,
        )

        store.selectTrip("1:10:20")
        assertEquals(older, store.state.value.trips.selected)
        assertNull("the other drive's route is never shown", store.state.value.trips.selectedRoute)
        assertEquals("1:10:20", store.tripRouteToRead())

        // A reread that no longer has the picked drive falls back to the newest.
        store.onTripHistory(listOf(newer))
        assertEquals(newer, store.state.value.trips.selected)
    }

    @Test
    fun theDemoShowsSampleDrivesAndLeavesTheLoggedOnesAlone() {
        val store = LiveUiStateStore(nowMs = { 5_000_000_000L })
        val logged = TripSummary("1:10:20", 10L, 20L, 1_000.0)
        store.onTripHistory(listOf(logged))

        store.onTelemetry(sample { put("source", "demo") })
        val demo = store.state.value.trips
        assertTrue(demo.trips.none { it.routeKey == logged.routeKey })
        assertFalse(demo.exportable)
        assertTrue("the demo's selected drive has its route", (demo.selectedRoute?.points?.size ?: 0) > 1)
        assertNull("demo routes are never read from disk", store.tripRouteToRead())
        val other = demo.trips.first().routeKey
        store.selectTrip(other)
        assertEquals(
            other,
            store.state.value.trips.selectedRoute
                ?.routeKey,
        )

        store.onStatus(JSONObject().put("state", "disconnected"))
        assertEquals(listOf(logged), store.state.value.trips.trips)
        assertEquals("1:10:20", store.tripRouteToRead())
    }

    @Test
    fun settingsCarryTheCostsToTheTripsTab() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(homeRate = 0.14, gasMpg = 38.0, gasPrice = 4.25) }
        val trips = store.state.value.trips
        assertEquals(0.14, trips.homeRate, 1e-9)
        assertEquals(38.0, trips.gasMpg ?: 0.0, 1e-9)
        assertEquals(4.25, trips.gasPrice, 1e-9)
    }
}
