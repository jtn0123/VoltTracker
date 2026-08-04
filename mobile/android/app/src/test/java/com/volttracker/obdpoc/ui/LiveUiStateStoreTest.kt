package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun missingFieldsKeepPriorValuesInsteadOfZeroing() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample())
        store.onTelemetry(JSONObject().put("updatedAt", 2_000L).put("speedKph", 32))
        val drive = store.state.value.drive

        assertEquals(19, drive.speedMph) // fresh
        assertEquals(364.0, drive.packVolts, 1e-9) // retained
        assertEquals("D", drive.gear) // retained
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
        repeat(40) { i -> store.onTelemetry(sample(updatedAt = 1_000L + i)) }
        val drive = store.state.value.drive

        assertEquals(30, drive.speedTrace.size)
        assertEquals(30, drive.powerTrace.size)
        // SOC is throttled to one sample per 30 s: 40 samples 1 ms apart → one point.
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
    fun statusUpdatesConnectionAcrossAllScreens() {
        val store = LiveUiStateStore()
        store.onStatus(
            JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"),
        )
        val s = store.state.value

        assertTrue(s.drive.connected)
        assertTrue(s.charge.connected)
        assertTrue(s.map.connected)
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
}
