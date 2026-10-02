package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/** The three steps from a fresh install to a live Volt, shown on Drive until there's something to show. */
internal val GET_STARTED_STEPS =
    listOf(
        "Plug a Bluetooth OBD-II adapter into the port under the dash on the driver's side. " +
            "An OBDLink MX+ reads the most Volt data.",
        "Turn the car on, then pair the adapter in your phone's Bluetooth settings.",
        "Tap Connect and pick it. The app remembers it and can reconnect by itself next time.",
    )

/**
 * Whether Drive should show [GetStartedCard] instead of the range card and tiles: no adapter was
 * ever chosen ([setupNeeded]), nothing is connecting or connected (the demo counts), and no drive
 * is known — this session's, or logged ones once the Trips history has been read.
 */
fun isFirstRun(
    drive: DriveUiState,
    setupNeeded: Boolean,
    hasTrips: Boolean,
): Boolean = setupNeeded && !drive.connected && !drive.connecting && drive.lastDrive == null && !hasTrips

/** First run on Drive: how to get from a fresh install to the car, in place of a card of dashes. */
@Composable
internal fun GetStartedCard(modifier: Modifier = Modifier) {
    VoltPanel(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
        VoltLabel("Get set up")
        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GET_STARTED_STEPS.forEachIndexed { i, step -> Step(i + 1, step) }
        }
        Text(
            "No car handy? Tap Demo to look around with sample data.",
            style = VoltType.caption,
            color = VoltColors.textSecondary,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun Step(
    number: Int,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(24.dp).background(VoltColors.surfaceElevated, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = VoltType.bodyStrong.copy(fontSize = 13.sp), color = VoltColors.textPrimary)
        }
        Text(text, style = VoltType.body, color = VoltColors.textPrimary, modifier = Modifier.weight(1f))
    }
}
