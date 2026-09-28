package com.volttracker.obdpoc

import android.content.SharedPreferences
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearancePrefs
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Reads the Compose Settings values from the shared prefs file and writes edits back to it. It goes
 * through the same owners the service and the classic dashboard use, so every UI sees one value:
 * - [AutoConnectController] for auto-connect.
 * - [EventNotificationCommands] for alerts and auto-scan (it also asks for the notification
 *   permission when an alert is turned on).
 * - [DashboardExperienceCommands] for keep-screen-awake and the end-of-drive recap.
 * - [SharedDisplayPrefs] for units, rates, the charge target and accessibility.
 * - [AppearancePrefs] for the Compose-only theme and Drive view choices.
 */
class ComposeSettingsStore(
    private val prefs: SharedPreferences,
    private val autoConnect: AutoConnectController,
    private val events: EventNotificationCommands,
    private val experience: DashboardExperienceCommands,
    private val formatDateTime: (Long) -> String = ::localDateTime,
) {
    private val shared = SharedDisplayPrefs(prefs)

    /** [base] with every stored setting filled in (non-settings fields are kept). */
    fun read(base: SettingsUiState): SettingsUiState {
        val alerts = EventNotificationPrefs(prefs)
        return base.copy(
            autoConnect = autoConnect.isEnabled(),
            notifyChargingComplete = alerts.chargeCompleteEnabled(),
            notifyNewCode = alerts.newDtcEnabled(),
            notifyBatteryLow = alerts.lowSocEnabled(),
            batteryLowPct = alerts.lowSocThresholdPct().roundToInt(),
            notifyPackTempHigh = alerts.highPackTempEnabled(),
            packTempHighC = alerts.highPackTempThresholdC().roundToInt(),
            notifyMaintenance = alerts.maintenanceDueEnabled(),
            endOfDriveRecap = prefs.getBoolean(DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY, false),
            autoScanCodes = alerts.autoScanOnConnectEnabled(),
            metricUnits = shared.units() == SharedDisplayPrefs.Units.METRIC,
            homeRate = shared.pricePerKwh(),
            publicRate = shared.publicPricePerKwh(),
            gasMpg = shared.mpg(),
            gasPrice = shared.gasPricePerGal(),
            chargeTargetPct = shared.chargeTargetSoc().roundToInt(),
            appearance = AppearancePrefs.read(prefs),
            darkStyle = AppearancePrefs.readDarkStyle(prefs),
            accent = AppearancePrefs.readAccent(prefs),
            keepScreenAwake = prefs.getBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, false),
            quietLiveData = shared.quietTelemetry(),
            fontScale = nearestTextSize(shared.fontScale()),
            highContrast = shared.highContrast(),
            driveDetailed = AppearancePrefs.readDriveDetailed(prefs),
            driveEnergyFlow = AppearancePrefs.readDriveEnergyFlow(prefs),
            lastBackupLabel =
                ComposeDataTools.lastBackupLabel(
                    prefs.getLong(BackupController.PREF_LAST_BACKUP_AT_MS, 0L),
                    prefs.getInt(BackupController.PREF_LAST_BACKUP_TRIPS, 0),
                    formatDateTime,
                ),
        )
    }

    /** Persists one edit to the store that owns it. */
    fun apply(change: SettingChange) {
        if (applyAlert(change)) return
        when (change) {
            is SettingChange.AutoConnect -> autoConnect.setEnabled(change.on)
            is SettingChange.EndOfDriveRecap -> experience.setTripSummaryEnabled(change.on)
            is SettingChange.KeepScreenAwake -> experience.setKeepScreenAwakeEnabled(change.on)
            is SettingChange.MetricUnits ->
                shared.setUnits(
                    if (change.metric) SharedDisplayPrefs.Units.METRIC else SharedDisplayPrefs.Units.IMPERIAL,
                )
            is SettingChange.HomeRate -> shared.setPricePerKwh(change.dollarsPerKwh)
            is SettingChange.PublicRate -> shared.setPublicPricePerKwh(change.dollarsPerKwh)
            is SettingChange.GasPrice -> shared.setGasPricePerGal(change.dollarsPerGal)
            is SettingChange.GasMpg -> shared.setMpg(change.mpg ?: 0.0)
            is SettingChange.ChargeTarget -> shared.setChargeTargetSoc(change.pct.toDouble())
            is SettingChange.QuietLiveData -> shared.setQuietTelemetry(change.on)
            is SettingChange.TextSize -> shared.setFontScale(change.scale)
            is SettingChange.HighContrast -> shared.setHighContrast(change.on)
            is SettingChange.Appearance -> AppearancePrefs.write(prefs, change.mode)
            is SettingChange.DarkTheme -> AppearancePrefs.writeDarkStyle(prefs, change.style)
            is SettingChange.Accent -> AppearancePrefs.writeAccent(prefs, change.accent)
            is SettingChange.DriveDetailed -> AppearancePrefs.writeDriveDetailed(prefs, change.detailed)
            is SettingChange.DriveEnergyFlow -> AppearancePrefs.writeDriveEnergyFlow(prefs, change.show)
            else -> Unit
        }
    }

    /** The alert toggles, which all go through [events]. False when [change] isn't one of them. */
    private fun applyAlert(change: SettingChange): Boolean {
        when (change) {
            is SettingChange.NotifyChargeComplete -> events.setChargeCompleteEnabled(change.on)
            is SettingChange.NotifyNewCode -> events.setNewDtcEnabled(change.on)
            is SettingChange.NotifyBatteryLow -> events.setLowSocEnabled(change.on, change.thresholdPct.toDouble())
            is SettingChange.NotifyPackTempHigh ->
                events.setHighPackTempEnabled(change.on, change.thresholdC.toDouble())
            is SettingChange.NotifyMaintenance -> events.setMaintenanceDueEnabled(change.on)
            is SettingChange.AutoScanCodes -> events.setAutoScanOnConnectEnabled(change.on)
            else -> return false
        }
        return true
    }

    private companion object {
        fun localDateTime(ms: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

        /** A stored scale outside the offered sizes snaps to the nearest one. */
        fun nearestTextSize(scale: Double): Double =
            SharedDisplayPrefs.FONT_SCALES.minBy { kotlin.math.abs(it - scale) }
    }
}
