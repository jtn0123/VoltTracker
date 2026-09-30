package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import org.json.JSONObject

/**
 * Live signals › All readings: every value in the newest sample, by name, with a filter box and
 * how old any stale one is. The native replacement for the classic dashboard's raw sensor list.
 */
@Composable
fun AllReadingsScreen(
    drive: DriveUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = drive.rawReadings
    val shown = all.matching(query)
    VoltScreen(
        title = "All readings",
        subtitle = if (drive.connected) "${all.size} readings in the latest sample" else "Not connected",
        dot = connectionDot(drive.connected),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (!drive.connected || all.isEmpty()) {
            VoltEmptyState(
                if (drive.connected) "Waiting for readings" else "Not connected",
                body = "Connect the adapter (or start the demo) to see every reading the car sends.",
            )
            return@VoltScreen
        }
        SearchField(query) { query = it }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            VoltEmptyState("No matches", body = "Nothing named \"${query.trim()}\" in this sample.")
            return@VoltScreen
        }
        VoltListCard {
            shown.forEachIndexed { i, reading ->
                if (i > 0) VoltListDivider()
                ReadingRow(reading)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, VoltColors.line2, VoltShapes.field)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        if (query.isEmpty()) Text("Filter readings", style = VoltType.body, color = VoltColors.textTertiary)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = VoltType.body.copy(color = VoltColors.textPrimary),
            cursorBrush = SolidColor(VoltColors.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter readings" },
        )
    }
}

@Composable
private fun ReadingRow(reading: RawReading) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(reading.label, style = VoltType.body, color = VoltColors.textSecondary)
            reading.ageText()?.let { Text(it, style = VoltType.caption, color = VoltColors.textTertiary) }
        }
        Text(reading.value, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
    }
}

@Preview(widthDp = 412, heightDp = 900)
@Composable
private fun AllReadingsPreview() {
    val sample =
        JSONObject()
            .put("packVoltage", 359.2)
            .put("socPct", 62.4)
            .put("speedKph", 48)
            .put("batteryTempC", 21)
            .put("aux12vVoltage", 12.6)
            .put("aux12vVoltageStaleMs", 18_000)
    VoltTheme {
        AllReadingsScreen(DriveUiState.demo.copy(rawReadings = rawReadings(sample, emptySet())))
    }
}
