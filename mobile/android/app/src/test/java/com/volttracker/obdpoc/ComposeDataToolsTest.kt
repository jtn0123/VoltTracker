package com.volttracker.obdpoc

import com.volttracker.obdpoc.ComposeDataTools.RestoreGate
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeDataToolsTest {
    @Test
    fun restoreIsRefusedWhileASessionIsLogging() {
        assertEquals(RestoreGate.STOP_LOGGING, ComposeDataTools.restoreGate(loggingActive = true, classicAlive = false))
        // Logging wins even when the classic dashboard is open: handing off would hit the same wall.
        assertEquals(RestoreGate.STOP_LOGGING, ComposeDataTools.restoreGate(loggingActive = true, classicAlive = true))
    }

    @Test
    fun restoreIsHandedToTheClassicDashboardWhileItIsOpen() {
        assertEquals(RestoreGate.USE_CLASSIC, ComposeDataTools.restoreGate(loggingActive = false, classicAlive = true))
    }

    @Test
    fun restoreRunsHereWhenNothingElseHoldsTheDatabase() {
        assertEquals(RestoreGate.OK, ComposeDataTools.restoreGate(loggingActive = false, classicAlive = false))
    }

    @Test
    fun lastBackupLabelReadsTheReceipt() {
        val format: (Long) -> String = { "T$it" }
        assertEquals(SettingsUiState.NO_BACKUP, ComposeDataTools.lastBackupLabel(0L, 5, format))
        assertEquals("Last backup T9", ComposeDataTools.lastBackupLabel(9L, 0, format))
        assertEquals("Last backup T9 · 1 trip", ComposeDataTools.lastBackupLabel(9L, 1, format))
        assertEquals("Last backup T9 · 12 trips", ComposeDataTools.lastBackupLabel(9L, 12, format))
    }

    @Test
    fun progressLabelShowsThePercentWhileBusyAndTheOutcomeOnceDone() {
        assertNull(ComposeDataTools.progressLabel(false, true, "Preparing", null, 40))
        assertEquals("Preparing backup · 40%", ComposeDataTools.progressLabel(true, true, "Preparing backup", "x", 40))
        assertEquals("Preparing backup", ComposeDataTools.progressLabel(true, true, "Preparing backup", null, -1))
        assertEquals(
            "Backup ready — Pick where to send it",
            ComposeDataTools.progressLabel(true, false, "Backup ready", "Pick where to send it", 100),
        )
        assertNull(ComposeDataTools.progressLabel(true, false, " ", "", -1))
        assertEquals(
            "Backup ready. Choose where to save it.",
            ComposeDataTools.progressLabel(true, false, "Backup ready", "Backup ready. Choose where to save it.", 100),
        )
    }

    @Test
    fun classicPresenceCountsLiveInstancesAndNeverGoesNegative() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        ClassicDashboardPresence.onDestroyed()
        assertFalse(ClassicDashboardPresence.isAlive())
        ClassicDashboardPresence.onCreated()
        ClassicDashboardPresence.onCreated()
        ClassicDashboardPresence.onDestroyed()
        assertTrue(ClassicDashboardPresence.isAlive())
        ClassicDashboardPresence.onDestroyed()
        assertFalse(ClassicDashboardPresence.isAlive())
    }
}
