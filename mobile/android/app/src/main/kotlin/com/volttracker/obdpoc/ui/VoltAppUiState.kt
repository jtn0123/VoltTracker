package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState

/**
 * One immutable snapshot of everything the Compose dashboard renders.
 * The live store mutates this atomically; screens stay pure functions of it.
 */
data class VoltAppUiState(
    val drive: DriveUiState = DriveUiState(),
    val charge: ChargeUiState = ChargeUiState(),
    val trips: TripsUiState = TripsUiState(),
    val insights: InsightsUiState = InsightsUiState(),
    val car: CarUiState = CarUiState(),
    val diag: DiagUiState = DiagUiState(),
    val settings: SettingsUiState = SettingsUiState(),
)
