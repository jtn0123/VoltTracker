package com.volttracker.obdpoc.ui.settings

import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.OledAccent

/**
 * One edit made on a Settings page. The screen emits these through a single callback; the host
 * persists each to the store it belongs to. The service, the classic dashboard and the Compose UI
 * all read the same values.
 */
sealed interface SettingChange {
    // Connection
    data class AutoConnect(
        val on: Boolean,
    ) : SettingChange

    // Alerts
    data class NotifyChargeComplete(
        val on: Boolean,
    ) : SettingChange

    data class NotifyNewCode(
        val on: Boolean,
    ) : SettingChange

    data class NotifyBatteryLow(
        val on: Boolean,
        val thresholdPct: Int,
    ) : SettingChange

    data class NotifyPackTempHigh(
        val on: Boolean,
        val thresholdC: Int,
    ) : SettingChange

    data class NotifyMaintenance(
        val on: Boolean,
    ) : SettingChange

    data class EndOfDriveRecap(
        val on: Boolean,
    ) : SettingChange

    data class AutoScanCodes(
        val on: Boolean,
    ) : SettingChange

    // Units & rates. A rate of 0 (or a null MPG) clears it.
    data class MetricUnits(
        val metric: Boolean,
    ) : SettingChange

    data class HomeRate(
        val dollarsPerKwh: Double,
    ) : SettingChange

    data class PublicRate(
        val dollarsPerKwh: Double,
    ) : SettingChange

    data class GasPrice(
        val dollarsPerGal: Double,
    ) : SettingChange

    data class GasMpg(
        val mpg: Double?,
    ) : SettingChange

    data class ChargeTarget(
        val pct: Int,
    ) : SettingChange

    // Display
    data class Appearance(
        val mode: AppearanceMode,
    ) : SettingChange

    data class DarkTheme(
        val style: DarkStyle,
    ) : SettingChange

    data class Accent(
        val accent: OledAccent,
    ) : SettingChange

    data class KeepScreenAwake(
        val on: Boolean,
    ) : SettingChange

    data class QuietLiveData(
        val on: Boolean,
    ) : SettingChange

    data class TextSize(
        val scale: Double,
    ) : SettingChange

    data class HighContrast(
        val on: Boolean,
    ) : SettingChange

    data class DriveDetailed(
        val detailed: Boolean,
    ) : SettingChange

    data class DriveEnergyFlow(
        val show: Boolean,
    ) : SettingChange
}
