package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.map.MapUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState

/**
 * One immutable snapshot of everything the Compose dashboard renders.
 * The live store mutates this atomically; screens stay pure functions of it.
 */
data class VoltAppUiState(
    val drive: DriveUiState = DriveUiState(),
    val charge: ChargeUiState = ChargeUiState(),
    val map: MapUiState = MapUiState(),
    val insights: InsightsUiState = InsightsUiState(),
    val diag: DiagUiState = DiagUiState(),
    val settings: SettingsUiState = SettingsUiState(),
)
