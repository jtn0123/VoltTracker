package com.volttracker.obdpoc.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltFigure
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.receipt.ReceiptBody
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlin.math.roundToInt

/**
 * One drive in full, opened from the Trips list: its route on the map, the headline figures, a
 * receipt of what it measured and what it cost, and share / export.
 */
@Composable
fun TripReceiptScreen(
    state: TripsUiState,
    onBack: () -> Unit,
    onShare: (subject: String, text: String) -> Unit = { _, _ -> },
    onExport: (TripExport) -> Unit = {},
    onEditInClassic: (routeKey: String) -> Unit = {},
) {
    val trip = state.selected
    val receipt = trip?.let { state.receipt(it, h24 = LocalVoltPrefs.current.clock24h) }
    VoltScreen(
        title = receipt?.title ?: "Drive",
        subtitle = receipt?.whenLine,
        onBack = onBack,
        showGear = false,
    ) {
        if (trip == null || receipt == null) {
            VoltEmptyState(
                "This drive isn't available",
                Modifier.padding(top = 8.dp),
                body = "It may have been deleted. Go back to see your drives.",
            )
            return@VoltScreen
        }
        TripMapCard(state)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp)) {
            val u = state.units
            VoltFigure("Distance", u.distanceOneDecimal(trip.miles), u.distanceUnit, Modifier.weight(1f))
            VoltFigure(
                "Electric",
                ((trip.evShare ?: 1.0) * PERCENT).roundToInt().toString(),
                "%",
                Modifier.weight(1f),
                valueColor = VoltColors.energy,
            )
            VoltFigure(
                "Efficiency",
                u.efficiencyValue(trip.miPerKwh) ?: DASH,
                u.efficiencyUnit,
                Modifier.weight(1f),
            )
        }
        ReceiptBody(
            receipt,
            onShare = { onShare(receipt.title, receipt.shareText()) },
            noRatesHint = NO_RATES,
        ) {
            if (state.exportable) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoltButton("Export GPX", Modifier.weight(1f)) {
                        onExport(TripExport.One(trip.routeKey, TripExport.GPX))
                    }
                    VoltButton("Export CSV", Modifier.weight(1f)) {
                        onExport(TripExport.One(trip.routeKey, TripExport.CSV))
                    }
                }
                // Naming and starring a drive still live on the classic dashboard's receipt.
                VoltButton(EDIT_IN_CLASSIC, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    onEditInClassic(trip.routeKey)
                }
                Text(
                    text = EDIT_IN_CLASSIC_HINT,
                    style = VoltType.caption,
                    color = VoltColors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            } else {
                Text(
                    text = "Demo drives can't be exported",
                    style = VoltType.caption,
                    color = VoltColors.textTertiary,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
        }
    }
}

private const val PERCENT = 100
internal const val EDIT_IN_CLASSIC = "Rename or star this drive"
private const val EDIT_IN_CLASSIC_HINT = "Opens this drive in the classic dashboard."
private const val NO_RATES =
    "Set your electricity rate, gas price and MPG in Settings → Costs & rates to see what this drive cost."

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 1400)
@Composable
private fun TripReceiptPreview() {
    VoltTheme { TripReceiptScreen(TripsUiState.demo, onBack = {}) }
}
