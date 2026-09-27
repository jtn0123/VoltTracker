package com.volttracker.obdpoc.ui.car

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.NavBadge
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltListRow
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.theme.VoltTheme

/**
 * The Car tab: the vehicle's own state and the way into Health (diagnostics). Body signals
 * (tyres, locks, 12V, climate) and car controls join this screen in a later pass.
 */
@Composable
fun CarScreen(
    diag: DiagUiState,
    charge: ChargeUiState,
    modifier: Modifier = Modifier,
) {
    val nav = LocalVoltNav.current
    VoltScreen(title = "Car", subtitle = "Chevrolet Volt", modifier = modifier) {
        VoltListCard {
            VoltListRow(
                icon = VoltIcons.Pulse,
                title = "Vehicle health",
                subtitle = healthSummary(diag),
                tone = healthTone(diag),
                onClick = nav.openHealth,
            )
            VoltListDivider()
            VoltListRow(
                icon = VoltIcons.Cells,
                title = "HV battery",
                subtitle = charge.cellBalanceLabel ?: "Cell balance appears after a battery read",
                tone = PillTone.EV,
            )
        }
    }
}

/** "No trouble codes · Last scan 2h ago" / "2 trouble codes · …" for the Health row. */
fun healthSummary(diag: DiagUiState): String {
    val count = diag.codes.size
    val codes =
        when (count) {
            0 -> "No trouble codes"
            1 -> "1 trouble code"
            else -> "$count trouble codes"
        }
    return listOfNotNull(codes, diag.lastScanLabel).joinToString(" · ")
}

/** Health row tint: green when clean, red for any alert-level code, amber otherwise. */
fun healthTone(diag: DiagUiState): PillTone =
    when {
        diag.codes.isEmpty() -> PillTone.EV
        diag.codes.any { it.severity == DtcSeverity.ALERT } -> PillTone.BAD
        else -> PillTone.WARN
    }

/** The Car tab's nav badge: shown only while trouble codes are stored. */
fun carBadge(diag: DiagUiState): NavBadge? =
    when (healthTone(diag)) {
        PillTone.BAD -> NavBadge.BAD
        PillTone.WARN -> NavBadge.WARN
        else -> null
    }

@Composable
@Preview(widthDp = 412, heightDp = 915)
private fun CarScreenPreview() {
    VoltTheme { CarScreen(DiagUiState.demo, ChargeUiState.demo) }
}
