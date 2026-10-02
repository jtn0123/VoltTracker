package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.ButtonStyle
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectAction
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.demoAction
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * Health › Live signals: the key live readings, grouped, in the chosen units. Every raw sensor
 * is one tap away in All readings.
 */
@Composable
fun LiveSignalsScreen(
    drive: DriveUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenAllReadings: () -> Unit = {},
) {
    VoltScreen(
        title = "Live signals",
        subtitle = liveSignalsLine(drive),
        dot = connectionDot(drive.connected, drive.connecting),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (!drive.connected) {
            val nav = LocalVoltNav.current
            VoltEmptyState(
                "Not connected",
                body = "Connect the adapter to watch the car's readings live.",
                action = nav.connectAction(),
                secondAction = nav.demoAction(),
            )
        } else {
            liveSignalGroups(drive).forEach { group ->
                VoltLabel(group.title, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
                VoltListCard {
                    group.rows.forEachIndexed { i, row ->
                        if (i > 0) VoltListDivider()
                        SignalRowView(row)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        VoltButton(
            text = "All raw readings",
            onClick = onOpenAllReadings,
            style = ButtonStyle.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Every value the car sends, by name.",
            style = VoltType.caption,
            color = VoltColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun SignalRowView(row: SignalRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, style = VoltType.body, color = VoltColors.textSecondary, modifier = Modifier.weight(1f))
        Text(row.value, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
    }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun LiveSignalsPreview() {
    VoltTheme {
        LiveSignalsScreen(DriveUiState.demo.copy(signalCount = 83))
    }
}
