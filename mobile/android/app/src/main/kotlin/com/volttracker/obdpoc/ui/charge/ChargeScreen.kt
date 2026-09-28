package com.volttracker.obdpoc.ui.charge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltFigure
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltPill
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.ambientAlpha
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.unitStyle
import com.volttracker.obdpoc.ui.components.voltAmbient
import com.volttracker.obdpoc.ui.drive.ArcGeometry
import com.volttracker.obdpoc.ui.drive.ChargeEta
import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.drive.shortDurationLabel
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Charge tab (mockups `S.charge`): a mini ring with time to full, the session's added
 * energy / cost / pack temperature, the SOC curve with its projection to the charge limit,
 * and recent sessions with this month's total.
 */
@Composable
fun ChargeScreen(
    state: ChargeUiState,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    val glow = if (state.charging) pal.ev.copy(alpha = ambientAlpha(pal)) else Color.Transparent
    Box(modifier = modifier.fillMaxSize().voltAmbient(glow)) {
        VoltScreen(
            title = "Charge",
            subtitle = state.subtitle,
            dot = connectionDot(state.connected),
        ) {
            ChargeHero(state)
            Spacer(Modifier.height(10.dp))
            ChargeFigures(state)
            if (state.charging && state.chartSpan() != null) {
                Spacer(Modifier.height(10.dp))
                SessionCard(state)
            }
            Spacer(Modifier.height(10.dp))
            RecentSessions(state)
        }
    }
}

@Composable
private fun ChargeHero(state: ChargeUiState) {
    VoltPanel(padding = PaddingValues(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniRing(state)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (state.charging) ChargingSide(state) else IdleSide(state)
            }
        }
    }
}

@Composable
private fun ChargingSide(state: ChargeUiState) {
    VoltPill("Charging", PillTone.EV)
    Spacer(Modifier.height(14.dp))
    val text = VoltColors.textPrimary
    when (val eta = state.eta) {
        is ChargeEta.Finish -> {
            Text(
                text =
                    buildAnnotatedString {
                        append(if (state.targetSoc >= FULL) "Full by " else "${state.targetSoc}% by ")
                        val clock =
                            SpanStyle(color = text, fontWeight = FontWeight.SemiBold, fontFamily = VoltFonts.barlow)
                        withStyle(clock) {
                            append(clockLabel(state.sampleAtMs, eta.remainingMs))
                        }
                    },
                style = VoltType.bodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal),
                color = VoltColors.textSecondary,
            )
            Text(
                text = "${shortDurationLabel(eta.remainingMs)} remaining",
                style = VoltType.body,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ChargeEta.NearlyFull -> SideLine("Topping off — nearly there")
        ChargeEta.Estimating -> SideLine(estimatingText(state.targetSoc))
        null -> {
            val atLimit = state.shownSocPercent >= state.targetSoc
            SideLine(if (atLimit) "At your ${state.targetSoc}% limit" else estimatingText(state.targetSoc))
        }
    }
    Spacer(Modifier.height(12.dp))
    val parts =
        listOfNotNull(
            state.acVolts?.let { "${it.roundToInt()} V" },
            state.acAmps?.let { "${it.roundToInt()} A" },
        )
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = VoltColors.energy, fontSize = 26.sp)) {
                    append(oneDecimal(state.chargeKw))
                }
                withStyle(unitStyle(14)) {
                    append(" kW")
                    parts.forEach { append(" · $it") }
                }
            },
        style = VoltType.value,
        color = VoltColors.textPrimary,
    )
}

@Composable
private fun IdleSide(state: ChargeUiState) {
    if (state.connected) {
        VoltPill("Not charging", PillTone.NEUTRAL)
    } else {
        VoltPill("Not connected", PillTone.NEUTRAL, dot = false)
    }
    Spacer(Modifier.height(12.dp))
    val last = state.sessions.firstOrNull()
    if (last != null) {
        SideLine("Last charge ${sessionWhen(last.startedAtMs)}")
        Text(
            text = lastChargeDetail(last),
            style = VoltType.caption,
            color = VoltColors.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    } else if (state.connected) {
        SideLine("Plug in the car to see time to full")
    } else {
        // Not connected is about the OBD adapter, not the charger.
        SideLine("Connect the adapter to follow a charge")
    }
}

/** "Estimating time to full…", or to the charge limit when one is set below 100 %. */
private fun estimatingText(targetSoc: Int): String =
    if (targetSoc >= FULL) "Estimating time to full…" else "Estimating time to $targetSoc%…"

/** "L2 · 24 → 91% · 11.8 kWh" for the unplugged hero. */
private fun lastChargeDetail(last: ChargeSession): String =
    listOfNotNull(
        sessionDetail(last.level, last.fromSoc, last.toSoc),
        last.energyKwh?.let { "${oneDecimal(it)} kWh" },
    ).joinToString(" · ")

@Composable
private fun SideLine(text: String) {
    Text(text = text, style = VoltType.body, color = VoltColors.textSecondary)
}

/** The mockups' 152 px `ring()`: SOC fill, the charge's start marker and a knob at the tip. */
@Composable
private fun MiniRing(state: ChargeUiState) {
    val pal = LocalVoltPalette.current
    val soc = state.shownSocPercent
    val known = state.connected || state.socPercent > 0
    Box(
        modifier =
            Modifier
                .size(RING_DP.dp)
                .semantics { contentDescription = "Battery ${soc.roundToInt()} percent" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RING_STROKE.dp.toPx()
            val r = size.minDimension / 2 - stroke / 2 - 2.dp.toPx()
            ringArc(r, ArcGeometry.START_DEG, ArcGeometry.END_DEG, pal.track, stroke)
            if (!known) return@Canvas
            val from = state.fromSoc
            if (state.charging && from != null) {
                ringArc(r, ArcGeometry.socToDeg(from), ArcGeometry.END_DEG, pal.ev.copy(alpha = FROM_TINT), stroke)
            }
            ringArc(r, ArcGeometry.START_DEG, ArcGeometry.socToDeg(soc), pal.ev, stroke)
            if (state.charging && from != null) {
                val marker = polar(r, ArcGeometry.socToDeg(from))
                drawCircle(pal.bg.copy(alpha = 0.8f), radius = 2.5.dp.toPx(), center = marker)
            }
            drawCircle(pal.bg, radius = 4.dp.toPx(), center = polar(r, ArcGeometry.socToDeg(soc)))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text =
                    buildAnnotatedString {
                        append(if (known) "${soc.toInt()}" else "--")
                        withStyle(SpanStyle(fontSize = 22.sp, color = VoltColors.textSecondary)) { append("%") }
                    },
                style = VoltType.display.copy(fontSize = 56.sp, lineHeight = 56.sp),
                color = VoltColors.textPrimary,
            )
            state.evRangeMiles?.let {
                Text(text = state.units.distanceText(it), style = VoltType.body, color = VoltColors.textSecondary)
            }
        }
    }
}

private fun DrawScope.ringArc(
    r: Float,
    fromDeg: Float,
    toDeg: Float,
    color: Color,
    stroke: Float,
) {
    val sweep = (toDeg - fromDeg).coerceAtLeast(MIN_SWEEP)
    drawArc(
        color = color,
        startAngle = fromDeg - 90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

private fun DrawScope.polar(
    r: Float,
    deg: Float,
): Offset {
    val a = Math.toRadians((deg - 90f).toDouble())
    return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
}

/**
 * Added / Cost / Battery (mockups `.kv3`), on a card like every other group of figures; the
 * last charge's figures while unplugged (the hero above already names it the last charge).
 */
@Composable
private fun ChargeFigures(state: ChargeUiState) {
    val last = state.sessions.firstOrNull().takeIf { !state.charging }
    val kwh = if (state.charging) state.addedKwh else last?.energyKwh
    val rate = last?.let { state.rateFor(it) } ?: state.homeRate
    val units = state.units
    VoltPanel {
        Row(modifier = Modifier.fillMaxWidth()) {
            VoltFigure("Added", kwh?.let(::oneDecimal) ?: "--", "kWh", Modifier.weight(1f))
            VoltFigure("Cost", costText(kwh, rate) ?: "--", null, Modifier.weight(1f))
            VoltFigure(
                "Battery",
                state.packTempF?.let { units.temp(it.toDouble()).toString() } ?: "--",
                units.tempUnit,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SessionCard(state: ChargeUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltLabel("This session")
            Chip("Charge limit ${state.targetSoc}%")
        }
        Spacer(Modifier.height(10.dp))
        ChargeSessionChart(state)
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        style = VoltType.caption,
        color = VoltColors.textSecondary,
        modifier =
            Modifier
                .clip(VoltShapes.chip)
                .background(VoltColors.surfaceElevated)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun RecentSessions(state: ChargeUiState) {
    val rows = state.sessionRows()
    VoltPanel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltLabel("Recent sessions")
            state.monthSummary(state.sampleAtMs.takeIf { it > 0 } ?: System.currentTimeMillis())?.let {
                Text(text = it, style = VoltType.caption, color = VoltColors.textSecondary)
            }
        }
        if (rows.isEmpty()) {
            Text(
                text =
                    when (state.history) {
                        HistoryLoad.LOADING -> "Loading charges…"
                        HistoryLoad.FAILED ->
                            "Charges couldn't be read. They'll load the next time you open Charge."
                        HistoryLoad.LOADED ->
                            "No charges logged yet. Sessions appear here after you charge with the adapter connected."
                    },
                style = VoltType.body,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        rows.forEach { row -> SessionRowView(row) }
    }
}

@Composable
private fun SessionRowView(row: SessionRow) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(VoltColors.hairline))
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.title, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
                if (row.live) {
                    Spacer(Modifier.width(8.dp))
                    VoltPill("Live", PillTone.EV, dot = false, small = true)
                }
            }
            Text(
                text = row.detail,
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(top = 1.dp),
            )
            SocBar(row.fromSoc, row.toSoc)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text =
                    buildAnnotatedString {
                        append(row.energyKwh?.let(::oneDecimal) ?: "--")
                        withStyle(unitStyle(11)) {
                            append(" kWh")
                        }
                    },
                style = VoltType.valueSmall,
                color = VoltColors.textPrimary,
            )
            row.cost?.let {
                Text(
                    text = it,
                    style = VoltType.body,
                    color = VoltColors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The from → to SOC span on a 0–100 % track (mockups `.sess-bar`). */
@Composable
private fun SocBar(
    fromSoc: Int?,
    toSoc: Int?,
) {
    val pal = LocalVoltPalette.current
    val to = toSoc ?: return
    val from = (fromSoc ?: to).coerceIn(0, PERCENT)
    Canvas(
        Modifier
            .padding(top = 7.dp)
            .width(SOC_BAR_DP.dp)
            .height(4.dp),
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(pal.track, cornerRadius = r)
        val x0 = size.width * from / PERCENT
        val x1 = size.width * to.coerceIn(from, PERCENT) / PERCENT
        val fill = Size((x1 - x0).coerceAtLeast(size.height), size.height)
        drawRoundRect(pal.ev, topLeft = Offset(x0, 0f), size = fill, cornerRadius = r)
    }
}

/** A unit after a number ("kWh", "°F"): prose face, muted. */
private const val RING_DP = 152
private const val RING_STROKE = 12
private const val FROM_TINT = 0.14f
private const val MIN_SWEEP = 0.5f
private const val FULL = 100
private const val PERCENT = 100
private const val SOC_BAR_DP = 170

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun ChargeScreenPreview() {
    VoltTheme { ChargeScreen(ChargeUiState.demo) }
}
