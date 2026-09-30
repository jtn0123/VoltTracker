package com.volttracker.obdpoc.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.IconCircleButton
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltFade
import com.volttracker.obdpoc.ui.components.VoltFigure
import com.volttracker.obdpoc.ui.components.VoltGroupLabel
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLoading
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.glideState
import com.volttracker.obdpoc.ui.components.unitStyle
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import com.volttracker.obdpoc.ui.units.VoltUnits

/**
 * The Trips tab (mockups `S.trips`): this month's drives in the header, the selected drive on
 * the map card, electric share / efficiency / savings, and the drives grouped by day. Tapping a
 * drive shows it on the map; the share button exports it.
 */
@Composable
fun TripsScreen(
    state: TripsUiState,
    onSelect: (String) -> Unit = {},
    onExport: (TripExport) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val selectedKey = state.selected?.routeKey
    var shownKey by rememberSaveable { mutableStateOf(selectedKey) }
    // A newly picked drive shows on the map at the top of the page.
    LaunchedEffect(selectedKey) {
        if (selectedKey != shownKey) {
            shownKey = selectedKey
            scroll.animateScrollTo(0)
        }
    }
    VoltScreen(
        title = "Trips",
        subtitle = state.subtitle(),
        dot = connectionDot(state.connected),
        scrollState = scroll,
        actions = { if (state.trips.isNotEmpty()) ExportMenu(state, onExport) },
    ) {
        // Loading → drives (or → "none yet") cross-fades rather than popping a full page in.
        VoltFade(if (state.trips.isEmpty()) state.history else null, label = "trips") { empty ->
            Column {
                val notice = Modifier.padding(top = 8.dp)
                when (empty) {
                    HistoryLoad.LOADING -> VoltLoading("Loading drives…", notice, rows = LOADING_ROWS)
                    HistoryLoad.FAILED ->
                        VoltEmptyState(
                            "Drives couldn't be read",
                            notice,
                            body = "Your logged drives are safe. They'll load the next time you open Trips.",
                        )
                    HistoryLoad.LOADED ->
                        VoltEmptyState(
                            "No drives logged yet",
                            notice,
                            body =
                                "Drives appear here after you drive with the adapter connected, " +
                                    "with the route when location is on.",
                        )
                    // The drives themselves. (Skipped while fading out after the last one is removed.)
                    null ->
                        if (state.trips.isNotEmpty()) {
                            TripMapCard(state)
                            TripsFigures(state)
                            state.groups().forEach { group ->
                                VoltGroupLabel(group.label, Modifier.padding(top = 2.dp))
                                group.trips.forEach { trip ->
                                    TripRow(trip, trip.routeKey == selectedKey, state.units) {
                                        onSelect(trip.routeKey)
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ExportMenu(
    state: TripsUiState,
    onExport: (TripExport) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconCircleButton(VoltIcons.Share, "Export drives", { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = VoltColors.surface,
        ) {
            val key = state.selected?.routeKey
            val pick: (TripExport) -> Unit = {
                open = false
                onExport(it)
            }
            ExportItem("This drive as GPX", state.exportable && key != null) {
                key?.let { pick(TripExport.One(it, TripExport.GPX)) }
            }
            ExportItem("This drive as CSV", state.exportable && key != null) {
                key?.let { pick(TripExport.One(it, TripExport.CSV)) }
            }
            ExportItem("All drives as CSV", state.exportable) { pick(TripExport.All) }
            if (!state.exportable) {
                Text(
                    text = "Demo drives can't be exported",
                    style = VoltType.caption,
                    color = VoltColors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExportItem(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, style = VoltType.body) },
        onClick = onClick,
        enabled = enabled,
        colors =
            MenuDefaults.itemColors(
                textColor = VoltColors.textPrimary,
                disabledTextColor = VoltColors.textTertiary,
            ),
    )
}

@Composable
private fun TripsFigures(state: TripsUiState) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp)) {
        VoltFigure(
            "Electric",
            state.electricPct()?.toString() ?: DASH,
            "%",
            Modifier.weight(1f),
            valueColor = VoltColors.energy,
        )
        VoltFigure(
            "Avg",
            state.units.efficiencyValue(state.avgMiPerKwh()) ?: DASH,
            state.units.efficiencyUnit,
            Modifier.weight(1f),
        )
        VoltFigure("Saved", state.savedVsGas()?.let(::wholeDollars) ?: DASH, "vs gas", Modifier.weight(1f))
    }
}

@Composable
private fun TripRow(
    trip: TripSummary,
    selected: Boolean,
    units: VoltUnits,
    onClick: () -> Unit,
) {
    val shape = VoltShapes.tile
    val mode = trip.mode
    val bar = VoltColors.accent
    // Card fill, a visible outline and an accent bar mark the selected drive (the plain card on
    // the OLED canvas alone was too subtle); picking another drive fades the marking across.
    val marked = glideState(if (selected) 1f else 0f, "trip-selected")
    val fill = VoltColors.surface
    val outline = VoltColors.line2
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    val shown = marked.value
                    if (shown <= 0f) return@drawBehind
                    val radius = CornerRadius(VoltShapes.TileRadius.toPx())
                    drawRoundRect(fill, cornerRadius = radius, alpha = shown)
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = radius,
                        style = Stroke(stroke),
                        alpha = shown,
                    )
                    val w = SELECTED_BAR.toPx()
                    drawRoundRect(
                        color = bar,
                        topLeft = Offset(0f, size.height * SELECTED_BAR_INSET),
                        size = Size(w, size.height * (1 - 2 * SELECTED_BAR_INSET)),
                        cornerRadius = CornerRadius(w / 2),
                        alpha = shown,
                    )
                }.clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquare(
            icon = if (mode == TripMode.EV) VoltIcons.Bolt else VoltIcons.Fuel,
            tone = if (mode == TripMode.EV) PillTone.EV else PillTone.GAS,
            size = 36.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                trip.title(),
                style = VoltType.bodyStrong,
                color = VoltColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                trip.whenLine(h24 = LocalVoltPrefs.current.clock24h),
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
            if (mode == TripMode.MIXED) SplitBar(trip.evShare ?: 0.0)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text =
                    buildAnnotatedString {
                        append(units.distanceOneDecimal(trip.miles))
                        withStyle(unitStyle(11)) { append(" ${units.distanceUnit}") }
                    },
                style = VoltType.valueSmall,
                color = VoltColors.textPrimary,
            )
            trip.efficiencyText(units)?.let {
                Text(
                    text = it,
                    style = VoltType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (mode == TripMode.EV) VoltColors.energy else VoltColors.gas,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Electric then gas share of a mixed drive (mockups `.trip-split`). */
@Composable
private fun SplitBar(evShare: Double) {
    val ev = evShare.toFloat().coerceIn(SPLIT_MIN, 1f - SPLIT_MIN)
    Row(
        modifier = Modifier.padding(top = 6.dp).width(150.dp).height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SplitPart(ev, VoltColors.energy)
        SplitPart(1f - ev, VoltColors.gas)
    }
}

@Composable
private fun RowScope.SplitPart(
    weight: Float,
    color: Color,
) {
    Spacer(
        Modifier
            .weight(weight)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

private const val SPLIT_MIN = 0.02f

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 1100)
@Composable
private fun TripsScreenPreview() {
    VoltTheme { TripsScreen(TripsUiState.demo) }
}

private val SELECTED_BAR = 3.dp
private const val SELECTED_BAR_INSET = 0.22f

/** Placeholder rows while the drives are read: enough to fill the page like the list will. */
private const val LOADING_ROWS = 5
