package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import com.volttracker.obdpoc.ui.trips.TripExport

/** Everything the dashboard can ask its host to do. Defaults are no-ops (previews, tests). */
data class VoltAppActions(
    val onOpenClassicDashboard: () -> Unit = {},
    /** Connect to the remembered adapter. False = none chosen yet, so the app opens the picker. */
    val onConnect: () -> Boolean = { true },
    val onStartDemo: () -> Unit = {},
    val onStopDemo: () -> Unit = {},
    val onCheckForUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    /** A stored setting changed (Settings pages, or Drive's Focus/Detailed toggle). */
    val onSettingChange: (SettingChange) -> Unit = {},
    /** A one-off Settings tool (test connection, wait for adapter, diagnostics, backup / restore / export). */
    val onSettingsCommand: (SettingsCommand) -> Unit = {},
    /**
     * The visible screen changed, as the classic dashboard's view name ("drive", "map", "charge",
     * "insights", "diagnostics", "settings"), so the keep-screen-awake rule is shared.
     */
    val onScreenShown: (String) -> Unit = {},
    /** Pull to refresh, or "Try again": re-read the saved history behind the screen that is showing. */
    val onRefresh: () -> Unit = {},
    /** Trips: show this drive (by route key) on the map. */
    val onSelectTrip: (String) -> Unit = {},
    /** Trips: export one drive (GPX / CSV) or all of them. */
    val onExportTrip: (TripExport) -> Unit = {},
    /** Insights: summarise this span. */
    val onInsightsPeriod: (InsightsPeriod) -> Unit = {},
    /** Car: ask for a car command by wire name (the host confirms it and checks every gate). */
    val onCarControl: (String) -> Unit = {},
    /** Car: turn car controls on (native warning + PIN) or off. */
    val onCarControlsEnabled: (Boolean) -> Unit = {},
    /** Car: one minute of continuous body-bus listening, frames logged for decoder checks. */
    val onBodyTest: () -> Unit = {},
    /** Health: read the car's trouble codes (the demo only simulates it). */
    val onScanCodes: () -> Unit = {},
    /** Health: clear the car's trouble codes, after the host's confirmation. */
    val onClearCodes: () -> Unit = {},
    /** Health: share this plain-text report. */
    val onShareHealthReport: (String) -> Unit = {},
)
