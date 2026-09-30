package com.volttracker.obdpoc.ui.charge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltFigure
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.receipt.ReceiptBody
import com.volttracker.obdpoc.ui.receipt.money
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme

/**
 * One logged charge in full, opened from Charge → Recent sessions: the battery it filled, the
 * energy, time and cost, the range it added, and what those miles would have cost on gas.
 */
@Composable
fun ChargeReceiptScreen(
    state: ChargeUiState,
    startedAtMs: Long?,
    worth: ChargeWorth,
    onBack: () -> Unit,
    onShare: (subject: String, text: String) -> Unit = { _, _ -> },
) {
    val session = state.session(startedAtMs)
    val receipt = session?.let { state.receipt(it, worth, h24 = LocalVoltPrefs.current.clock24h) }
    VoltScreen(
        title = receipt?.title ?: "Charge",
        subtitle = receipt?.whenLine,
        onBack = onBack,
        showGear = false,
    ) {
        if (session == null || receipt == null) {
            VoltEmptyState(
                "This charge isn't available",
                Modifier.padding(top = 8.dp),
                body = "It may have been deleted. Go back to see your charges.",
            )
            return@VoltScreen
        }
        BatteryFill(session)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp)) {
            VoltFigure("Added", session.energyKwh?.let(::oneDecimal) ?: DASH, "kWh", Modifier.weight(1f))
            val gained = session.toSoc?.let { to -> session.fromSoc?.let { to - it } }
            VoltFigure(
                "Battery",
                gained?.let { "+$it" } ?: DASH,
                "%",
                Modifier.weight(1f),
                valueColor = VoltColors.energy,
            )
            val rate = state.rateFor(session)
            VoltFigure(
                "Cost",
                session.energyKwh?.takeIf { rate > 0.0 }?.let { money(it * rate) } ?: DASH,
                null,
                Modifier.weight(1f),
            )
        }
        ReceiptBody(
            receipt,
            onShare = { onShare(receipt.title, receipt.shareText()) },
            noRatesHint = "Set your electricity rate in Settings → Costs & rates to see what this charge cost.",
        )
    }
}

/** The battery before (faint) and after (green) the charge, on a full-width 0–100 % track. */
@Composable
private fun BatteryFill(session: ChargeSession) {
    val to = session.toSoc ?: return
    val from = (session.fromSoc ?: to).coerceIn(0, PERCENT)
    val pal = LocalVoltPalette.current
    VoltPanel {
        VoltLabel("Battery")
        Canvas(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(14.dp)
                .semantics { contentDescription = "Battery from $from% to $to%" },
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(pal.track, cornerRadius = r)
            val x0 = size.width * from / PERCENT
            val x1 = size.width * to.coerceIn(from, PERCENT) / PERCENT
            drawRoundRect(
                pal.ev.copy(alpha = FROM_ALPHA),
                size = Size(x0.coerceAtLeast(size.height), size.height),
                cornerRadius = r,
            )
            drawRoundRect(
                pal.ev,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
                cornerRadius = r,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            VoltLabel("$from%", Modifier.weight(1f), color = VoltColors.textSecondary)
            VoltLabel("$to%", color = VoltColors.energy)
        }
    }
}

private const val PERCENT = 100
private const val FROM_ALPHA = 0.3f

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun ChargeReceiptPreview() {
    val state = ChargeUiState.demo
    VoltTheme {
        ChargeReceiptScreen(state, state.sessions.first().startedAtMs, ChargeWorth(4.0, 42.0, 4.5), onBack = {})
    }
}
