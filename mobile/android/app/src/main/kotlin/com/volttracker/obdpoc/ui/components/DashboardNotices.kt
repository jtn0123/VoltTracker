package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.VoltAppActions
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import java.text.DateFormat
import java.util.Date

/** Persistent, independently labelled history, connection and recording problems on every native page. */
@Composable
fun DashboardNotices(
    state: VoltAppUiState,
    actions: VoltAppActions,
    onConnect: () -> Unit,
    onAdapter: () -> Unit,
) {
    state.historyResetAtMs?.let { atMs ->
        VoltPanel(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), padding = PaddingValues(12.dp)) {
            Text("Saved history was reset", style = VoltType.body, color = VoltColors.warn)
            Text(historyResetDetail(atMs), style = VoltType.caption, color = VoltColors.textSecondary)
            TextButton(onClick = actions.onDismissHistoryReset) { Text("Dismiss") }
        }
    }
    state.recordingWarning?.let { detail ->
        VoltPanel(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), padding = PaddingValues(12.dp)) {
            Text("Recording incomplete", style = VoltType.body, color = VoltColors.warn)
            Text(detail, style = VoltType.caption, color = VoltColors.textSecondary)
            Text(
                "Live readings can continue. Free storage, then stop and reconnect to start a new recording.",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
    }
    state.connectionFailure?.let { failure ->
        VoltPanel(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), padding = PaddingValues(12.dp)) {
            Column {
                Text("Connection needs attention", style = VoltType.body, color = VoltColors.warn)
                Text(failure.detail, style = VoltType.caption, color = VoltColors.textSecondary)
                failure.competingApps?.let {
                    Text("Other Bluetooth apps: $it", style = VoltType.caption, color = VoltColors.textSecondary)
                }
                androidx.compose.foundation.layout.FlowRow {
                    TextButton(onClick = onConnect, enabled = !state.drive.connecting) { Text("Retry") }
                    TextButton(onClick = onAdapter) { Text("Adapter") }
                    TextButton(onClick = actions.onOpenTroubleshooter) { Text("Troubleshooter") }
                    TextButton(onClick = actions.onDismissConnectionFailure) { Text("Dismiss") }
                }
            }
        }
    }
}

/** What the reset notice says: when it happened, that the damaged copy was kept, and the way back. */
internal fun historyResetDetail(
    atMs: Long,
    dates: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM),
): String =
    "On ${dates.format(Date(atMs))} the saved history on this phone was found damaged. Volt Tracker kept " +
        "the damaged copy and started a new, empty history. To get your drives back, restore a backup " +
        "in Settings → Restore."
