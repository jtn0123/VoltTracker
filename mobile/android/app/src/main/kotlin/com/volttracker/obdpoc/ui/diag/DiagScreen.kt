package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.VoltStat
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/** Car › Health: trouble codes, live session, data health. */
@Composable
fun DiagScreen(
    state: DiagUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    VoltScreen(
        title = "Health",
        subtitle = "Car › Diagnostics",
        onBack = onBack,
        modifier = modifier,
    ) {
        CodesHero(state)
        Spacer(Modifier.height(26.dp))
        if (state.codes.isNotEmpty()) {
            CodesPanel(state)
            Spacer(Modifier.height(14.dp))
        }
        SessionPanel(state)
        Spacer(Modifier.height(14.dp))
        HealthPanel(state)
    }
}

@Composable
private fun CodesHero(state: DiagUiState) {
    val clean = state.codes.isEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (clean) "✓" else "${state.codes.size}",
            style = VoltType.display,
            color = if (clean) VoltColors.energy else VoltColors.warn,
        )
        Text(
            text = if (clean) "NO TROUBLE CODES" else "CHECK-ENGINE CODES",
            style = VoltType.heroUnit,
            color = VoltColors.textTertiary,
        )
        if (state.lastScanLabel != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.lastScanLabel,
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Scan now", accent = true, onClick = {})
            VoltButton(text = "Clear codes…", onClick = {})
        }
    }
}

@Composable
private fun CodesPanel(state: DiagUiState) {
    VoltPanel {
        VoltLabel("Codes")
        state.codes.forEachIndexed { i, code ->
            if (i > 0) HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
            DtcRow(code)
        }
    }
}

private fun severityColor(
    pal: VoltPalette,
    severity: DtcSeverity,
): Color =
    when (severity) {
        DtcSeverity.INFO -> pal.textSecondary
        DtcSeverity.WARNING -> pal.warn
        DtcSeverity.ALERT -> pal.alert
    }

@Composable
private fun DtcRow(code: DtcCode) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = code.code,
                style = VoltType.valueSmall,
                color = severityColor(LocalVoltPalette.current, code.severity),
            )
            if (code.pending) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "PENDING",
                    style = VoltType.label,
                    color = VoltColors.textTertiary,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(VoltColors.surfaceElevated)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(text = code.title, style = VoltType.body, color = VoltColors.textPrimary)
        Spacer(Modifier.height(3.dp))
        Text(text = code.adviceLabel, style = VoltType.caption, color = VoltColors.textSecondary)
        Spacer(Modifier.height(3.dp))
        Text(text = code.seenLabel, style = VoltType.caption, color = VoltColors.textTertiary)
    }
}

@Composable
private fun SessionPanel(state: DiagUiState) {
    VoltPanel {
        VoltLabel("OBD session")
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VoltStat(label = "Adapter", value = state.adapterLabel)
            VoltStat(label = "Samples", value = String.format(Locale.US, "%,d", state.sampleCount))
            VoltStat(label = "Runtime", value = state.runtimeLabel, alignEnd = true)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.vehicleStateLabel,
            style = VoltType.caption,
            color = VoltColors.textSecondary,
        )
    }
}

@Composable
private fun HealthPanel(state: DiagUiState) {
    VoltPanel {
        val okCount = state.health.count { it.ok }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            VoltLabel("Data health")
            Text(
                text = "$okCount/${state.health.size} ok",
                style = VoltType.valueSmall,
                color = if (okCount == state.health.size) VoltColors.energy else VoltColors.textSecondary,
            )
        }
        Spacer(Modifier.height(4.dp))
        state.health.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
            HealthRow(item)
        }
    }
}

@Composable
private fun HealthRow(item: HealthItem) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (item.ok) VoltColors.energy else VoltColors.warn),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(text = item.name, style = VoltType.body, color = VoltColors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(text = item.detail, style = VoltType.caption, color = VoltColors.textTertiary)
            }
        }
        Text(text = item.stateLabel, style = VoltType.caption, color = VoltColors.textSecondary)
    }
}

@Preview(widthDp = 412, heightDp = 1300)
@Composable
private fun DiagScreenPreview() {
    VoltTheme { DiagScreen(DiagUiState.demo) }
}
