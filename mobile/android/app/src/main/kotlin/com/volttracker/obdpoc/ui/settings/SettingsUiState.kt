package com.volttracker.obdpoc.ui.settings

/**
 * Everything the Settings screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 */
data class SettingsUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    // Connection
    val adapterLabel: String = "--",
    val autoConnect: Boolean = true,
    val backgroundWaitLabel: String = "10 min",
    // Alerts
    val notifyChargingComplete: Boolean = true,
    val notifyNewCode: Boolean = true,
    val notifyBatteryLow: Boolean = false,
    val batteryLowLabel: String = "below 20%",
    val notifyPackTempHigh: Boolean = false,
    val packTempHighLabel: String = "above 45°C",
    val notifyMaintenance: Boolean = false,
    val endOfDriveRecap: Boolean = false,
    val autoScanCodes: Boolean = false,
    // Units & rates
    val unitsLabel: String = "mi · °F",
    val homeRateLabel: String = "not set",
    val publicRateLabel: String = "not set",
    val gasMpgLabel: String = "30 MPG",
    val gasPriceLabel: String = "not set",
    val chargeTargetLabel: String = "100%",
    // Display
    val keepScreenAwake: Boolean = false,
    val quietLiveData: Boolean = true,
    val textSizeLabel: String = "Default",
    val highContrast: Boolean = false,
    val driveTilesLabel: String = "Detailed",
    // Data
    val lastBackupLabel: String = "No backup recorded on this phone yet",
    val versionLabel: String = "",
    // App updates (GitHub Releases). Null status = no check yet this session.
    val updateStatusLabel: String? = null,
    /** Tag of a newer published build, e.g. "v0.35.0"; null = none known. */
    val updateAvailableTag: String? = null,
    /** 0..99 while a download runs; null otherwise. */
    val updateDownloadPercent: Int? = null,
) {
    companion object {
        /** Sample state mirroring the demo scenario. */
        val demo =
            SettingsUiState(
                connected = true,
                statusLabel = "Live · 1 Hz",
                adapterLabel = "OBDLink MX+",
                versionLabel = "Volt Tracker 0.33.0",
            )
    }
}
