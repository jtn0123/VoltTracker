package com.volttracker.obdpoc.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.VoltBottomNav
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltStat
import com.volttracker.obdpoc.ui.components.VoltStatusPill
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/**
 * The Map tab: trip selector, real CARTO/OSM basemap with the
 * efficiency-colored route + mode toggle overlaid, scrubber with
 * position telemetry, and the trip detail card.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    modifier: Modifier = Modifier,
    composedMap: ComposedMap? = null,
    onSelectTab: (VoltTab) -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(VoltColors.bg),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 18.dp, bottom = 118.dp),
        ) {
            Box(Modifier.padding(horizontal = 20.dp)) { MapHeader(state) }
            Spacer(Modifier.height(22.dp))
            TripChips(state)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.padding(horizontal = 20.dp)) { RoutePanel(state, composedMap) }
            Spacer(Modifier.height(14.dp))
            if (state.scrubberStats != null) {
                Box(Modifier.padding(horizontal = 20.dp)) { ScrubberPanel(state, state.scrubberStats) }
                Spacer(Modifier.height(14.dp))
            }
            Box(Modifier.padding(horizontal = 20.dp)) { TripDetail(state) }
        }
        VoltBottomNav(
            selected = VoltTab.MAP,
            onSelect = onSelectTab,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun MapHeader(state: MapUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Map", style = VoltType.screenTitle, color = VoltColors.textPrimary)
        VoltStatusPill(
            text = state.statusLabel,
            dotColor = if (state.connected) VoltColors.energy else VoltColors.textTertiary,
        )
    }
}

@Composable
private fun TripChips(state: MapUiState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.trips.forEach { trip ->
            Column(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (trip.selected) VoltColors.surfaceElevated else VoltColors.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = trip.title,
                    style = VoltType.body,
                    color = if (trip.selected) VoltColors.textPrimary else VoltColors.textSecondary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text =
                        String.format(
                            Locale.US,
                            "%s · %.0f mi%s",
                            trip.whenLabel,
                            trip.miles,
                            trip.miPerKwh?.let { String.format(Locale.US, " · %.1f mi/kWh", it) } ?: "",
                        ),
                    style = VoltType.caption,
                    color = VoltColors.textTertiary,
                )
            }
        }
    }
}

private fun qualityColor(quality: RouteQuality): Color =
    when (quality) {
        RouteQuality.GREAT -> VoltColors.energy
        RouteQuality.AVERAGE -> VoltColors.warn
        RouteQuality.POOR -> VoltColors.alert
    }

private fun segmentColor(
    point: RoutePoint,
    mode: MapViewMode,
): Color =
    when (mode) {
        MapViewMode.EFFICIENCY -> qualityColor(point.quality)
        MapViewMode.ROUTES, MapViewMode.STOPS -> VoltColors.energy
        MapViewMode.HEAT -> {
            val f = point.speedFrac.coerceIn(0f, 1f)
            if (f < 0.5f) {
                lerp(VoltColors.regen, VoltColors.warn, f * 2f)
            } else {
                lerp(VoltColors.warn, VoltColors.alert, (f - 0.5f) * 2f)
            }
        }
    }

/** The route + markers drawn over the basemap (or the offline fallback grid). */
@Composable
private fun RouteOverlay(
    state: MapUiState,
    projectPoint: (RoutePoint) -> Offset,
    drawFallbackGrid: Boolean,
    modifier: Modifier = Modifier,
) {
    val route = state.route
    Canvas(modifier = modifier) {
        if (drawFallbackGrid) {
            val gridStep = size.width / 14f
            var gx = gridStep / 2f
            while (gx < size.width) {
                var gy = gridStep / 2f
                while (gy < size.height) {
                    drawCircle(color = VoltColors.hairline, radius = 1.6f, center = Offset(gx, gy))
                    gy += gridStep
                }
                gx += gridStep
            }
        }
        if (route.size < 2) return@Canvas
        // Soft glow pass under the colored trace.
        for (i in 1 until route.size) {
            drawLine(
                color = segmentColor(route[i], state.viewMode).copy(alpha = 0.25f),
                start = projectPoint(route[i - 1]),
                end = projectPoint(route[i]),
                strokeWidth = 22f,
                cap = StrokeCap.Round,
            )
        }
        for (i in 1 until route.size) {
            drawLine(
                color = segmentColor(route[i], state.viewMode),
                start = projectPoint(route[i - 1]),
                end = projectPoint(route[i]),
                strokeWidth = 9f,
                cap = StrokeCap.Round,
            )
        }
        // Stops (lights, parking) — always visible in Stops mode.
        if (state.viewMode == MapViewMode.STOPS) {
            route.filter { it.stop }.forEach { p ->
                drawCircle(color = VoltColors.warn, radius = 13f, center = projectPoint(p))
                drawCircle(color = VoltColors.bg, radius = 6f, center = projectPoint(p))
            }
        }
        // Start / end markers.
        drawCircle(color = VoltColors.energy, radius = 16f, center = projectPoint(route.first()))
        drawCircle(color = VoltColors.bg, radius = 8f, center = projectPoint(route.first()))
        drawCircle(color = VoltColors.alert, radius = 16f, center = projectPoint(route.last()))
        drawCircle(color = VoltColors.bg, radius = 8f, center = projectPoint(route.last()))
        // Scrubber position marker.
        val fIndex = state.scrubberFraction.coerceIn(0f, 1f) * (route.size - 1)
        val i0 = fIndex.toInt().coerceIn(0, route.size - 1)
        val i1 = (i0 + 1).coerceAtMost(route.size - 1)
        val a = projectPoint(route[i0])
        val b = projectPoint(route[i1])
        val t = fIndex - i0
        val pos = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        drawCircle(color = VoltColors.textPrimary, radius = 15f, center = pos)
        drawCircle(color = VoltColors.bg, radius = 10f, center = pos)
        drawCircle(color = VoltColors.textPrimary, radius = 5f, center = pos)
    }
}

@Composable
private fun ModeToggle(selected: MapViewMode) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(VoltColors.bg.copy(alpha = 0.72f))
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MapViewMode.entries.forEach { mode ->
            val active = mode == selected
            Text(
                text = mode.label,
                style = VoltType.label.copy(fontSize = 11.sp),
                color = if (active) VoltColors.textPrimary else VoltColors.textSecondary,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) VoltColors.surfaceElevated else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun MapLegend(mode: MapViewMode) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(VoltColors.bg.copy(alpha = 0.72f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            MapViewMode.HEAT -> {
                LegendDot("Slow", VoltColors.regen)
                LegendDot("Fast", VoltColors.alert)
            }
            MapViewMode.STOPS -> {
                LegendDot("Route", VoltColors.energy)
                LegendDot("Stop", VoltColors.warn)
            }
            else -> {
                LegendDot("Great", VoltColors.energy)
                LegendDot("Avg", VoltColors.warn)
                LegendDot("Poor", VoltColors.alert)
            }
        }
    }
}

@Composable
private fun RoutePanel(
    state: MapUiState,
    composedMap: ComposedMap?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(VoltColors.surface),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
        ) {
            if (composedMap != null) {
                Image(
                    bitmap = composedMap.bitmap.asImageBitmap(),
                    contentDescription = "Route basemap",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            RouteOverlayWithProjection(state, composedMap)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
            ) { ModeToggle(selected = state.viewMode) }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
            ) { MapLegend(state.viewMode) }
            Text(
                text = "© OpenStreetMap · © CARTO",
                style = VoltType.caption.copy(fontSize = 10.sp),
                color = VoltColors.textSecondary,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(topStart = 8.dp))
                        .background(VoltColors.bg.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltButton(text = "▶ Play", accent = true, onClick = {})
            VoltButton(text = "Full map", onClick = {})
            VoltButton(text = "Details", onClick = {})
        }
    }
}

/** Chooses the tile projection when a basemap is present, else a bbox fit. */
@Composable
private fun RouteOverlayWithProjection(
    state: MapUiState,
    composedMap: ComposedMap?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val projectPoint: (RoutePoint) -> Offset
        if (composedMap != null) {
            val bmpW = composedMap.bitmap.width.toFloat()
            val bmpH = composedMap.bitmap.height.toFloat()
            projectPoint = { p ->
                val o = composedMap.projection.offsetOf(p.lat, p.lon)
                Offset(o.x * (wPx / bmpW), o.y * (hPx / bmpH))
            }
        } else {
            val route = state.route
            val minLat = route.minOfOrNull { it.lat } ?: 0.0
            val maxLat = route.maxOfOrNull { it.lat } ?: 1.0
            val minLon = route.minOfOrNull { it.lon } ?: 0.0
            val maxLon = route.maxOfOrNull { it.lon } ?: 1.0
            val spanLat = (maxLat - minLat).coerceAtLeast(1e-9)
            val spanLon = (maxLon - minLon).coerceAtLeast(1e-9)
            projectPoint = { p ->
                Offset(
                    (0.1f + 0.8f * ((p.lon - minLon) / spanLon).toFloat()) * wPx,
                    (0.1f + 0.8f * (1f - ((p.lat - minLat) / spanLat).toFloat())) * hPx,
                )
            }
        }
        RouteOverlay(
            state = state,
            projectPoint = projectPoint,
            drawFallbackGrid = composedMap == null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LegendDot(
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(6.dp))
        Text(text = label, style = VoltType.caption, color = VoltColors.textSecondary)
    }
}

/** Scrubber telemetry: the six position metrics the legacy map showed. */
@Composable
private fun ScrubberPanel(
    state: MapUiState,
    stats: ScrubberStats,
) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            VoltLabel("At scrubber")
            Text(
                text = String.format(Locale.US, "%.1f mi in", stats.distanceMiles),
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            KeyValue("Speed", "${stats.speedMph} mph")
            KeyValue("Elevation", "${stats.elevationFt} ft")
            KeyValue("Grade", "${stats.gradePercent}%")
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            KeyValue("Battery", "${stats.batteryPercent}%", valueColor = VoltColors.energy)
            KeyValue(
                "Efficiency",
                stats.miPerKwh?.let { String.format(Locale.US, "%.1f mi/kWh", it) } ?: "--",
            )
            KeyValue("Position", "${(state.scrubberFraction * 100).toInt()}%")
        }
    }
}

@Composable
private fun KeyValue(
    label: String,
    value: String,
    valueColor: Color = VoltColors.textPrimary,
) {
    Column {
        VoltLabel(label)
        Spacer(Modifier.height(5.dp))
        Text(text = value, style = VoltType.valueSmall, color = valueColor)
    }
}

@Composable
private fun TripDetail(state: MapUiState) {
    VoltPanel {
        VoltLabel(state.tripSubtitle)
        Spacer(Modifier.height(4.dp))
        Text(text = state.tripTitle, style = VoltType.value, color = VoltColors.textPrimary)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VoltStat(label = "Distance", value = String.format(Locale.US, "%.0f", state.distanceMiles), unit = "mi")
            VoltStat(label = "Duration", value = state.durationLabel)
            VoltStat(label = "Avg", value = "${state.avgMph}", unit = "mph")
            VoltStat(
                label = "Energy",
                value = state.energyKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                unit = "kWh",
                alignEnd = true,
            )
        }
        Spacer(Modifier.height(24.dp))
        TripProfileChart(state)
    }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun MapScreenPreview() {
    VoltTheme { MapScreen(MapUiState.demo) }
}
