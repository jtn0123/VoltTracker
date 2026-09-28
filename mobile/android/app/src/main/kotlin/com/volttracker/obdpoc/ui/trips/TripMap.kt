package com.volttracker.obdpoc.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * The selected drive on a map card (mockups `.map-card`): its GPS track colored electric green
 * where the car drove on battery and gas amber where the engine did, start and end dots, an
 * "Engine on · N mi" marker where the engine first started, and the drive's summary chip.
 *
 * [ground] is the layer under the route, given the route's [MapViewport] (null while there is
 * no track to fit). It defaults to [TripMapGround]: a plain themed ground with a faint dot grid,
 * with Stadia street tiles over it when the app provides a [LocalMapTileLoader]. The route and
 * the tiles share the viewport's Web-Mercator projection, so the track sits on its streets.
 */
@Composable
fun TripMapCard(
    state: TripsUiState,
    modifier: Modifier = Modifier,
    ground: @Composable BoxScope.(MapViewport?) -> Unit = { TripMapGround(it) },
) {
    val trip = state.selected ?: return
    val route = state.selectedRoute
    val pal = LocalVoltPalette.current
    val shape = RoundedCornerShape(22.dp)
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MAP_DP.dp)
                .clip(shape)
                .background(pal.mapLand)
                .border(1.dp, pal.line, shape),
    ) {
        val points = route?.points.orEmpty()
        val viewport =
            remember(points, constraints, density) {
                if (points.size < 2) {
                    null
                } else {
                    with(density) {
                        fitViewport(
                            points,
                            Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                            padSide = 28.dp.toPx(),
                            padTop = 24.dp.toPx(),
                            padBottom = (CHIP_ROOM_DP + 8).dp.toPx(),
                            tileSizePx = MapViewport.TILE_DP.dp.toPx(),
                        )
                    }
                }
            }
        ground(viewport)
        if (viewport != null) {
            RouteCanvas(
                points = points,
                viewport = viewport,
                tripMiles = trip.miles,
                modifier =
                    Modifier.fillMaxSize().semantics {
                        contentDescription = routeDescription(trip)
                    },
            )
        } else {
            Text(
                text = if (route == null) "Loading route…" else "No GPS route for this drive",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(bottom = CHIP_ROOM_DP.dp / 2),
            )
        }
        MapChip(trip, Modifier.align(Alignment.BottomCenter).padding(10.dp))
    }
}

/** The default map ground: the card's mapLand fill with a faint dot grid, so it reads as a map without implying roads. */
@Composable
fun MapGround() {
    val pal = LocalVoltPalette.current
    Canvas(Modifier.fillMaxSize()) {
        val step = DOT_STEP_DP.dp.toPx()
        val radius = DOT_RADIUS_DP.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(pal.mapRoad, radius, Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun RouteCanvas(
    points: List<TripPoint>,
    viewport: MapViewport,
    tripMiles: Double,
    modifier: Modifier,
) {
    val pal = LocalVoltPalette.current
    val measurer = rememberTextMeasurer()
    val markStyle =
        VoltType.caption.copy(
            fontFamily = VoltFonts.hanken,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    val engineOn = TripRoute("", points).engineOn()
    Canvas(modifier) {
        val project: (TripPoint) -> Offset = viewport::project
        val casing = Path()
        points.forEachIndexed { i, p ->
            val o = project(p)
            if (i == 0) casing.moveTo(o.x, o.y) else casing.lineTo(o.x, o.y)
        }
        drawPath(
            casing,
            pal.bg.copy(alpha = CASING_ALPHA),
            style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        for (i in 1 until points.size) {
            drawLine(
                modeColor(pal, points[i].gas),
                project(points[i - 1]),
                project(points[i]),
                4.5.dp.toPx(),
                StrokeCap.Round,
            )
        }
        val start = project(points.first())
        drawCircle(pal.surface, 7.dp.toPx(), start)
        drawCircle(modeColor(pal, points.first().gas), 7.dp.toPx(), start, style = Stroke(3.dp.toPx()))
        val end = project(points.last())
        drawCircle(pal.surface, 9.5.dp.toPx(), end)
        drawCircle(modeColor(pal, points.last().gas), 8.dp.toPx(), end)
        engineOn?.let {
            drawEngineOn(
                project(points[it.index]),
                densify(points.map(project)),
                it.fraction * tripMiles,
                pal,
                measurer,
                markStyle,
            )
        }
    }
}

private fun DrawScope.drawEngineOn(
    at: Offset,
    track: List<Offset>,
    miles: Double,
    pal: VoltPalette,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val half = 4.dp.toPx()
    rotate(DIAMOND_DEG, pivot = at) {
        drawRoundRect(
            pal.text,
            topLeft = Offset(at.x - half, at.y - half),
            size = Size(half * 2, half * 2),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
    val label = measurer.measure("Engine on · ${milesText(miles)}", style.copy(color = pal.gas))
    // A small backed tag beside the marker, on whichever side covers the least of the track,
    // kept inside the card and clear of the chip.
    val padX = 6.dp.toPx()
    val padY = 2.dp.toPx()
    val w = label.size.width + 2 * padX
    val h = label.size.height + 2 * padY
    val gap = 10.dp.toPx()
    val edge = 8.dp.toPx()
    val floor = size.height - (CHIP_ROOM_DP + 8).dp.toPx()
    val candidates =
        listOf(
            Offset(at.x + gap, at.y + gap),
            Offset(at.x - gap - w, at.y + gap),
            Offset(at.x + gap, at.y - gap - h),
            Offset(at.x - gap - w, at.y - gap - h),
        ).map {
            Offset(
                it.x.coerceIn(edge, size.width - w - edge),
                it.y.coerceIn(edge, (floor - h).coerceAtLeast(edge)),
            )
        }
    // Counted with a margin for the stroke and the start / end markers, not just the line's centre.
    val clear = TAG_CLEAR_DP.dp.toPx()
    val (x, y) =
        candidates.minBy { c ->
            track.count { it.x in (c.x - clear)..(c.x + w + clear) && it.y in (c.y - clear)..(c.y + h + clear) }
        }
    drawRoundRect(
        pal.surface.copy(alpha = TAG_ALPHA),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2),
    )
    drawText(label, topLeft = Offset(x + padX, y + padY))
}

/** The track sampled every few pixels, so a label's overlap with it can be counted. */
private fun densify(track: List<Offset>): List<Offset> =
    track.zipWithNext().flatMap { (a, b) ->
        val steps = ((b - a).getDistance() / TRACK_SAMPLE_PX).toInt().coerceAtLeast(1)
        (0 until steps).map { i -> a + (b - a) * (i.toFloat() / steps) }
    } + track.takeLast(1)

private fun modeColor(
    pal: VoltPalette,
    gas: Boolean,
): Color = if (gas) pal.gas else pal.ev

@Composable
private fun MapChip(
    trip: TripSummary,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(VoltColors.surface)
                .border(1.dp, VoltColors.hairline, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(trip.title(), style = VoltType.bodyStrong, color = VoltColors.textPrimary, maxLines = 1)
            Text(trip.chipDetail(), style = VoltType.caption, color = VoltColors.textSecondary, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            val split = VoltType.caption.copy(fontWeight = FontWeight.SemiBold)
            Text("${milesText(trip.evMiles)} EV", style = split, color = VoltColors.energy)
            if (trip.mode != TripMode.EV) {
                Text("${milesText(trip.gasMiles)} gas", style = split, color = VoltColors.gas)
            }
        }
    }
}

private fun routeDescription(trip: TripSummary): String =
    if (trip.mode == TripMode.EV) {
        "Route map: ${milesText(trip.miles)}, all electric"
    } else {
        "Route map: ${milesText(trip.evMiles)} electric, ${milesText(trip.gasMiles)} on gas"
    }

private const val MAP_DP = 250
private const val CHIP_ROOM_DP = 72
private const val DOT_STEP_DP = 18
private const val DOT_RADIUS_DP = 1.2f
private const val CASING_ALPHA = 0.7f
private const val TAG_ALPHA = 0.9f
private const val TAG_CLEAR_DP = 8
private const val TRACK_SAMPLE_PX = 4f
private const val DIAMOND_DEG = 45f
