package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.carBadge
import com.volttracker.obdpoc.ui.car.healthSummary
import com.volttracker.obdpoc.ui.car.healthTone
import com.volttracker.obdpoc.ui.components.NavBadge
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure summaries behind the Car tab row, its nav badge, and the Settings index. */
class CarLogicTest {
    private val warnings = DiagUiState.demo
    private val clean = DiagUiState.demo.copy(codes = emptyList())
    private val alert =
        DiagUiState.demo.copy(
            codes =
                DiagUiState.demo.codes.mapIndexed { i, c ->
                    if (i ==
                        0
                    ) {
                        c.copy(severity = DtcSeverity.ALERT)
                    } else {
                        c
                    }
                },
        )

    @Test
    fun healthSummaryCountsCodesAndAppendsTheLastScan() {
        assertEquals("2 trouble codes · Last scan 2h ago", healthSummary(warnings))
        assertEquals("No trouble codes · Last scan 2h ago", healthSummary(clean))
        assertEquals("1 trouble code", healthSummary(DiagUiState(codes = warnings.codes.take(1))))
        assertEquals("No trouble codes", healthSummary(DiagUiState()))
    }

    @Test
    fun healthToneEscalatesWithTheWorstCode() {
        assertEquals(PillTone.EV, healthTone(clean))
        assertEquals(PillTone.WARN, healthTone(warnings))
        assertEquals(PillTone.BAD, healthTone(alert))
    }

    @Test
    fun carBadgeShowsOnlyWhileCodesAreStored() {
        assertNull(carBadge(clean))
        assertEquals(NavBadge.WARN, carBadge(warnings))
        assertEquals(NavBadge.BAD, carBadge(alert))
    }

    @Test
    fun alertsOnCountTalliesTheNotificationToggles() {
        assertEquals(2, SettingsUiState().alertsOnCount)
        assertEquals(
            6,
            SettingsUiState(
                notifyBatteryLow = true,
                notifyPackTempHigh = true,
                notifyMaintenance = true,
                endOfDriveRecap = true,
                autoScanCodes = true,
            ).alertsOnCount,
        )
    }
}
