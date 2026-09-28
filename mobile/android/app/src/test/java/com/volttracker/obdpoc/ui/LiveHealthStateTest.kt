package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.live.HealthHistoryHolder
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Health's codes from the saved history (found vs. earlier), the demo's simulated scan and clear, and busy state. */
class LiveHealthStateTest {
    private var clock = NOW
    private val store = LiveUiStateStore { clock }
    private val diag get() = store.state.value.diag

    private fun code(
        dtc: String,
        lastSeen: Long,
    ) = DtcCode(dtc, firstSeenMs = lastSeen, lastSeenMs = lastSeen)

    @Test
    fun beforeAnyHistoryTheCarIsNotScannedYet() {
        assertNull(diag.codes)
        store.onHealthHistory(HealthHistoryHolder.Logged(emptyList(), null, null))
        assertNull(diag.codes)
        assertEquals(NOW, diag.nowMs)
    }

    @Test
    fun aScanThatFoundNothingIsClean() {
        store.onHealthHistory(HealthHistoryHolder.Logged(emptyList(), NOW - HOUR, null))
        assertEquals(emptyList<DtcCode>(), diag.codes)
        assertEquals(NOW - HOUR, diag.scannedAtMs)
    }

    @Test
    fun codesTheLastScanDidntSeeAreEarlier() {
        val scan = NOW - HOUR
        store.onHealthHistory(
            HealthHistoryHolder.Logged(
                listOf(code("P0420", scan - 60_000L), code("P0171", scan - 3 * HOUR)),
                scannedAtMs = scan,
                clearedAtMs = null,
            ),
        )
        assertEquals(listOf("P0420"), diag.codes?.map { it.code })
        assertEquals(listOf("P0171"), diag.earlierCodes)
        assertEquals(scan, diag.scannedAtMs)
        assertNull(diag.clearedAtMs)
    }

    @Test
    fun anUntimedScanIsTimedByItsNewestCode() {
        store.onHealthHistory(
            HealthHistoryHolder.Logged(listOf(code("P0420", NOW - HOUR), code("P0171", NOW - 5 * HOUR)), null, null),
        )
        assertEquals(NOW - HOUR, diag.scannedAtMs)
        assertEquals(listOf("P0420"), diag.codes?.map { it.code })
        assertEquals(listOf("P0171"), diag.earlierCodes)
    }

    @Test
    fun aClearAfterTheScanMovesEveryCodeToEarlier() {
        store.onHealthHistory(
            HealthHistoryHolder.Logged(listOf(code("P0420", NOW - HOUR)), NOW - HOUR, NOW - 60_000L),
        )
        assertEquals(emptyList<DtcCode>(), diag.codes)
        assertEquals(listOf("P0420"), diag.earlierCodes)
        assertEquals(NOW - 60_000L, diag.clearedAtMs)
        // A scan after the clear supersedes it.
        store.onHealthHistory(
            HealthHistoryHolder.Logged(listOf(code("P0420", NOW)), NOW, NOW - 60_000L),
        )
        assertEquals(listOf("P0420"), diag.codes?.map { it.code })
        assertNull(diag.clearedAtMs)
    }

    @Test
    fun theDemoShowsItsOwnCodesAndSimulatesScanAndClear() {
        store.onHealthHistory(HealthHistoryHolder.Logged(listOf(code("P0171", NOW)), NOW, null))
        store.onStatus(JSONObject().put("state", "demo"))
        assertEquals(DiagUiState.demoAt(NOW).codes, diag.codes)
        clock = NOW + HOUR
        store.onDemoScan()
        assertEquals(listOf(NOW + HOUR, NOW + HOUR), diag.codes?.map { it.lastSeenMs })
        assertEquals(NOW + HOUR, diag.scannedAtMs)
        store.onDemoClear()
        assertEquals(emptyList<DtcCode>(), diag.codes)
        assertEquals(listOf("P0420", "P0011"), diag.earlierCodes)
        assertEquals(NOW + HOUR, diag.clearedAtMs)
        store.onDemoScan()
        assertEquals(2, diag.codes?.size)
        // Leaving the demo brings the real history back.
        store.onStatus(JSONObject().put("state", "disconnected"))
        assertEquals(listOf("P0171"), diag.codes?.map { it.code })
    }

    @Test
    fun aRunningScanOrClearIsBusy() {
        store.onStatus(JSONObject().put("state", "scanning").put("detail", "Reading stored codes"))
        assertEquals("Reading stored codes", diag.busyLabel)
        store.onStatus(JSONObject().put("state", "clearing-codes"))
        assertEquals("Clearing the car's trouble codes…", diag.busyLabel)
        store.onStatus(JSONObject().put("state", "scanning"))
        assertEquals("Reading the car's trouble codes…", diag.busyLabel)
        store.onStatus(JSONObject().put("state", "scan-complete"))
        assertNull(diag.busyLabel)
    }

    @Test
    fun capacityAhComesFromTheSample() {
        store.onTelemetry(JSONObject().put("updatedAt", NOW).put("capacityAh", 47.3))
        assertEquals(47.3, store.state.value.charge.capacityAh ?: 0.0, 0.001)
        store.onTelemetry(JSONObject().put("updatedAt", NOW + 1_000L).put("socPct", 50.0))
        assertEquals(47.3, store.state.value.charge.capacityAh ?: 0.0, 0.001)
    }

    private companion object {
        const val NOW = 1_777_585_320_000L
        const val HOUR = 3_600_000L
    }
}
