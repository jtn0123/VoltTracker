package com.volttracker.obdpoc.ui.settings

import com.volttracker.obdpoc.BuildConfig
import com.volttracker.obdpoc.ui.drive.TIRE_PLACARD_PSI
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.OledAccent
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the Settings screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 *
 * The persisted fields hold the real stored values. Their defaults are the ones the service and the
 * classic dashboard use when nothing is stored (see [com.volttracker.obdpoc.ui.live.SettingsPrefsReader]).
 * The labels are derived from those values, so the screen can never show a value that isn't stored.
 */
data class SettingsUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    /** The service is streaming Demo / Testing data rather than a real car. */
    val demoActive: Boolean = false,
    // Connection
    val adapterLabel: String = "--",
    /** Devices paired in Android's Bluetooth settings, OBD-looking ones first. */
    val pairedAdapters: List<PairedAdapter> = emptyList(),
    val adapterList: AdapterListState = AdapterListState.READY,
    /** The remembered adapter's address ("" when none is chosen yet). */
    val selectedAdapterAddress: String = "",
    val autoConnect: Boolean = false,
    /** A "wait for adapter" schedule is probing in the background. */
    val waitingForAdapter: Boolean = false,
    /** How long "wait for adapter" keeps checking once armed. */
    val adapterWaitMins: Int = 10,
    // Alerts
    val notifyChargingComplete: Boolean = true,
    val notifyNewCode: Boolean = true,
    val notifyBatteryLow: Boolean = false,
    val batteryLowPct: Int = 20,
    val notifyPackTempHigh: Boolean = false,
    val packTempHighC: Int = 45,
    val notifyMaintenance: Boolean = false,
    val endOfDriveRecap: Boolean = false,
    val autoScanCodes: Boolean = false,
    // Units & rates (0 / null = not set)
    val metricUnits: Boolean = false,
    val homeRate: Double = 0.0,
    val publicRate: Double = 0.0,
    val gasMpg: Double? = null,
    val gasPrice: Double = 0.0,
    val chargeTargetPct: Int = 100,
    /** The door-jamb tyre placard (cold, psi) the tyre readings are judged against. */
    val tirePlacardPsi: Double = TIRE_PLACARD_PSI,
    // Display
    // Appearance: follow the system theme, or pin light/dark.
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    /** The palette a dark [appearance] renders in. */
    val darkStyle: DarkStyle = DarkStyle.OLED,
    /** OLED Black's accent (ignored by Saddle Leather and the light theme). */
    val accent: OledAccent = OledAccent.CYAN,
    val keepScreenAwake: Boolean = false,
    val quietLiveData: Boolean = true,
    /** Text size multiplier: 1, 1.25 or 1.5. */
    val fontScale: Double = 1.0,
    val highContrast: Boolean = false,
    /** Drive opens on Detailed (the Cockpit) instead of Focus. */
    val driveDetailed: Boolean = false,
    /** Drive's Focus view shows the energy-flow card (opt-in). */
    val driveEnergyFlow: Boolean = false,
    // Data
    val lastBackupLabel: String = NO_BACKUP,
    /** The running (or just finished) backup / restore / export, e.g. "Preparing backup · 42%". */
    val dataTaskLabel: String? = null,
    val versionLabel: String = "",
    // App updates (GitHub Releases). Null status = no check yet this session.
    val updateStatusLabel: String? = null,
    /** Tag of a newer published build, e.g. "v0.35.0"; null = none known. */
    val updateAvailableTag: String? = null,
    /** 0..99 while a download runs; null otherwise. */
    val updateDownloadPercent: Int? = null,
) {
    /** How many alert notifications are switched on (the Settings index summary). */
    val alertsOnCount: Int
        get() =
            listOf(
                notifyChargingComplete,
                notifyNewCode,
                notifyBatteryLow,
                notifyPackTempHigh,
                notifyMaintenance,
                endOfDriveRecap,
            ).count { it }

    val unitsLabel: String get() = if (metricUnits) "Metric" else "Imperial"

    /** Settings → Units, for formatting. */
    val units: VoltUnits get() = VoltUnits.of(metricUnits)

    /** "38 psi" / "262 kPa": the placard in the chosen units. */
    val tirePlacardLabel: String get() = units.pressureText(tirePlacardPsi)

    val homeRateLabel: String get() = rateLabel(homeRate, "kWh")

    val publicRateLabel: String get() = if (publicRate > 0.0) rateLabel(publicRate, "kWh") else "same as home"

    /** "$4.29 / gal", or per litre in metric. */
    val gasPriceLabel: String get() = rateLabel(units.gasPrice(gasPrice), units.gasVolumeUnit)

    /** "38 mpg", or "6.2 L/100 km" in metric. */
    val gasMpgLabel: String
        get() =
            gasMpg?.let { mpg ->
                if (metricUnits) units.economyText(mpg) else "${formatNumber(mpg)} mpg"
            } ?: NOT_SET

    val chargeTargetLabel: String get() = "$chargeTargetPct%"

    val batteryLowLabel: String get() = "below $batteryLowPct%"

    val packTempHighLabel: String get() = "above ${temperatureLabel(packTempHighC)}"

    val adapterWaitLabel: String get() = "$adapterWaitMins min"

    val textSizeLabel: String get() = TEXT_SIZES.firstOrNull { it.first == fontScale }?.second ?: "Default"

    /** A whole-degree Celsius threshold in the chosen units. */
    fun temperatureLabel(celsius: Int): String =
        if (metricUnits) "$celsius°C" else "${(celsius * F_PER_C + F_OFFSET).roundToInt()}°F"

    companion object {
        const val NOT_SET = "not set"
        const val NO_BACKUP = "No backup recorded on this phone yet"

        /** The update check found nothing newer. */
        const val UP_TO_DATE = "You're up to date"

        // The choices the classic dashboard offers for these alerts (alerts.html).
        val BATTERY_LOW_CHOICES = listOf(10, 15, 20, 30)
        val PACK_TEMP_CHOICES = listOf(40, 45, 50, 55)
        val CHARGE_TARGET_PRESETS = listOf(80, 90, 100)

        // The classic dashboard's "notify when ready" durations (connection-tools.html).
        val ADAPTER_WAIT_CHOICES = listOf(5, 10, 15, 30)
        val TEXT_SIZES = listOf(1.0 to "Default", 1.25 to "Large", 1.5 to "Largest")

        private const val F_PER_C = 9.0 / 5.0
        private const val F_OFFSET = 32.0

        /** "$0.12 / kWh", or [NOT_SET] for 0. */
        fun rateLabel(
            value: Double,
            unit: String,
        ): String = if (value > 0.0) String.format(Locale.US, "$%.2f / %s", value, unit) else NOT_SET

        /** Drops a trailing ".0" so 30.0 reads "30" and 32.5 reads "32.5". */
        fun formatNumber(value: Double): String =
            if (value == Math.floor(value)) value.toLong().toString() else value.toString()

        /** Sample state mirroring the demo scenario. */
        val demo =
            SettingsUiState(
                connected = true,
                statusLabel = "Live",
                adapterLabel = "OBDLink MX+",
                autoConnect = true,
                notifyBatteryLow = true,
                homeRate = DEMO_HOME_RATE,
                gasPrice = DEMO_GAS_PRICE,
                gasMpg = DEMO_GAS_MPG,
                versionLabel = "Volt Tracker ${BuildConfig.VERSION_NAME}",
            )

        // One set of demo costs for every tab's sample state, so Settings and Insights agree.
        const val DEMO_HOME_RATE = 0.12
        const val DEMO_GAS_PRICE = 4.29
        const val DEMO_GAS_MPG = 38.0
    }
}
