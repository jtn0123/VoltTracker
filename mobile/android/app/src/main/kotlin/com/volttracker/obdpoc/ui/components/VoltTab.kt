package com.volttracker.obdpoc.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/** The five dashboard tabs, in nav order. Settings is a gear route, not a tab. */
enum class VoltTab(
    val label: String,
    val icon: () -> ImageVector,
) {
    DRIVE("Drive", { VoltIcons.Drive }),
    TRIPS("Trips", { VoltIcons.Trips }),
    CHARGE("Charge", { VoltIcons.Bolt }),
    INSIGHTS("Insights", { VoltIcons.Insights }),
    CAR("Car", { VoltIcons.Car }),
}
