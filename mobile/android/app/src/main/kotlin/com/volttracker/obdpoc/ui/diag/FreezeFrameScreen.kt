package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import com.volttracker.obdpoc.ui.units.VoltUnits

/**
 * Health › Freeze frame: the code the car saved a snapshot with, and what the car was doing at
 * that moment (speed, RPM, temperatures…) in the chosen units.
 */
@Composable
fun FreezeFrameScreen(
    state: DiagUiState,
    units: VoltUnits,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val dtc = state.freezeFrameDtc()
    VoltScreen(
        title = "Freeze frame",
        subtitle = dtc?.let { "Captured with $it" } ?: "Health › Freeze frame",
        dot = connectionDot(state.connected, state.connecting),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (dtc == null) {
            VoltEmptyState(
                "None stored",
                body = "The car saves a snapshot of its readings when it sets a trouble code. There isn't one now.",
            )
            return@VoltScreen
        }
        val code = state.codes?.firstOrNull { it.code == dtc }
        VoltLabel("Code", modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
        VoltListCard {
            CodeRow(dtc, code?.description ?: "Trouble code")
        }
        Spacer(Modifier.height(12.dp))
        val rows = freezeFrameRows(state.freezeFrame, units)
        if (rows.isNotEmpty()) {
            VoltLabel("When it was set", modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            VoltListCard {
                rows.forEachIndexed { i, row ->
                    if (i > 0) VoltListDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.label,
                            style = VoltType.body,
                            color = VoltColors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(row.value, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            state.freezeFrameNote(),
            style = VoltType.caption,
            color = VoltColors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CodeRow(
    code: String,
    description: String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(code, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
        Text(description, style = VoltType.caption, color = VoltColors.textSecondary)
    }
}

@Preview(widthDp = 412, heightDp = 900)
@Composable
private fun FreezeFramePreview() {
    VoltTheme {
        FreezeFrameScreen(DiagUiState.demo, VoltUnits())
    }
}
