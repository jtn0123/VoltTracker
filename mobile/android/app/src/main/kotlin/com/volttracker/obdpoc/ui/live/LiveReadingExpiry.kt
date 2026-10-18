package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.Gen2Status

/** Expiry changes presentation only; the session tracker keeps the charge's start and totals. */
internal fun DriveUiState.withoutFreshCharge(): DriveUiState =
    copy(
        phase = DrivePhase.UNKNOWN,
        chargeKw = 0.0,
        chargeAcVolts = null,
        chargeAcAmps = null,
        chargeLevel = null,
        chargeEta = null,
        evRangeMiles = null,
        gen2 = Gen2Status(),
    )

internal fun ChargeUiState.withoutFreshCharge(): ChargeUiState =
    copy(
        waitingForTelemetry = true,
        charging = false,
        chargeKw = 0.0,
        acVolts = null,
        acAmps = null,
        level = null,
        carChargeState = null,
        carChargeStart = null,
        evRangeMiles = null,
    )
