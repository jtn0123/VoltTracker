package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsCommand

/** Everything the dashboard can ask its host to do. Defaults are no-ops (previews, tests). */
data class VoltAppActions(
    val onOpenClassicDashboard: () -> Unit = {},
    val onConnect: () -> Unit = {},
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
)
