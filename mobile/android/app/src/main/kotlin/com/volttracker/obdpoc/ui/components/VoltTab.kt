package com.volttracker.obdpoc.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.graphics.vector.ImageVector

/** The six dashboard tabs, in nav order. */
enum class VoltTab(
    val label: String,
    val icon: ImageVector,
) {
    DRIVE("Drive", Icons.Rounded.Speed),
    MAP("Map", Icons.Rounded.Map),
    CHARGE("Charge", Icons.Rounded.Bolt),
    INSIGHTS("Insights", Icons.Rounded.Insights),
    DIAG("Diag", Icons.Rounded.MonitorHeart),
    SETTINGS("Settings", Icons.Rounded.Settings),
}
