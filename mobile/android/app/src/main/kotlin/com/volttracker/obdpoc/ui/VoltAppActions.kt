package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.theme.AppearanceMode

/** Everything the dashboard can ask its host to do. Defaults are no-ops (previews, tests). */
data class VoltAppActions(
    val onOpenClassicDashboard: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onStartDemo: () -> Unit = {},
    val onStopDemo: () -> Unit = {},
    val onCheckForUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onSetAppearance: (AppearanceMode) -> Unit = {},
)
