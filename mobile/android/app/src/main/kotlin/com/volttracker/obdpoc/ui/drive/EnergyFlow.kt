package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.CappedTextScale
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.rememberLoopPhase
import com.volttracker.obdpoc.ui.components.spoken
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/** Which energy paths are live, and what each node reads (mockups `flowStrip`). */
data class EnergyFlow(
    val caption: String,
    val gridActive: Boolean,
    val batteryActive: Boolean,
    val driveActive: Boolean,
    val engineActive: Boolean,
    /** Battery ↔ drive unit flows toward the battery (regen). */
    val regen: Boolean,
    val gridValue: String,
    val batteryValue: String,
    val driveValue: String,
    val engineValue: String,
)

/** Derives the flow diagram from the Drive state — pure, so it is unit-tested. */
fun energyFlow(state: DriveUiState): EnergyFlow {
    val plugged = state.phase == DrivePhase.CHARGING
    val driving = state.phase == DrivePhase.DRIVE
    val regen = state.regenerating
    val gasOn = state.gasDriving
    return EnergyFlow(
        caption =
            when {
                plugged -> "Grid → battery"
                !driving -> "Idle"
                regen -> "Regenerating"
                gasOn -> "Engine → drive unit"
                else -> "Battery → drive unit"
            },
        gridActive = plugged,
        batteryActive = plugged || (driving && !gasOn) || regen,
        driveActive = driving,
        engineActive = gasOn,
        regen = regen,
        gridValue = if (plugged) "${oneDecimal(state.chargeKw)} kW" else "Unplugged",
        batteryValue = if (state.connected) "${state.shownSocPercent.toInt()}%" else DASH,
        driveValue = if (driving) "${String.format(Locale.US, "%.0f", state.powerKw)} kW" else "Idle",
        engineValue = if (gasOn) "${String.format(Locale.US, "%,d", state.rpm)} rpm" else "Off",
    )
}

/** TalkBack's reading of the strip: the flow, then what each node reads. */
fun EnergyFlow.description(): String =
    "Energy flow: $caption. Grid ${spoken(gridValue)}, battery ${spoken(batteryValue)}, " +
        "drive unit ${spoken(driveValue)}, engine ${spoken(engineValue)}"

private const val STRIP_W = 380f
private const val STRIP_H = 150f
private const val NODE_Y = 62f
private const val NODE_R = 29f
private val NODE_X = floatArrayOf(44f, 146f, 246f, 336f)

/** Direction B's energy-flow strip as an optional Focus card: grid · battery · drive unit · engine. */
@Composable
fun EnergyFlowCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val flow = energyFlow(state)
    val pal = LocalVoltPalette.current
    val measurer = rememberTextMeasurer()
    val icons =
        listOf(
            rememberVectorPainter(VoltIcons.Plug),
            rememberVectorPainter(VoltIcons.Battery),
            rememberVectorPainter(VoltIcons.Motor),
            rememberVectorPainter(VoltIcons.Fuel),
        )
    val anyFlow = flow.gridActive || flow.regen || flow.engineActive || flow.driveActive
    // The dashes only march while energy is actually moving (and never with animations removed).
    val t = rememberLoopPhase(active = anyFlow, period = DASH_PERIOD, durationMs = FLOW_MS, label = "flow") ?: 0f
    val described = flow.description()
    VoltPanel(
        modifier =
            modifier.padding(top = 10.dp).semantics(mergeDescendants = true) {
                contentDescription = described
            },
        padding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltLabel("Energy flow")
            Text(
                text = flow.caption,
                style = VoltType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = VoltColors.textSecondary,
            )
        }
        CappedTextScale {
            Canvas(Modifier.fillMaxWidth().height(STRIP_H.dp).padding(top = 4.dp, bottom = 6.dp)) {
                drawFlow(flow, pal, measurer, icons, t)
            }
        }
    }
}

private fun DrawScope.drawFlow(
    flow: EnergyFlow,
    pal: VoltPalette,
    measurer: TextMeasurer,
    icons: List<VectorPainter>,
    t: Float,
) {
    val k = size.width / STRIP_W
    val y = NODE_Y * k

    fun link(
        i: Int,
        color: Color,
        forward: Boolean,
        on: Boolean,
    ) {
        val from = Offset((NODE_X[i] + NODE_R + 1) * k, y)
        val to = Offset((NODE_X[i + 1] - NODE_R - 1) * k, y)
        drawLine(pal.line2, from, to, strokeWidth = 2 * k, cap = StrokeCap.Round)
        if (on) {
            val phase = (if (forward) -t else t) * k
            drawLine(
                color,
                from,
                to,
                strokeWidth = 4 * k,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, DASH_PERIOD * k), phase),
            )
        }
    }
    val battColor =
        if (flow.gridActive || flow.regen) {
            pal.ev
        } else if (flow.engineActive) {
            pal.faint
        } else {
            pal.volt
        }
    val driveColor =
        if (flow.regen) {
            pal.ev
        } else if (flow.engineActive) {
            pal.gas
        } else {
            pal.volt
        }
    link(0, pal.ev, forward = true, on = flow.gridActive)
    link(
        1,
        if (flow.regen) pal.ev else pal.volt,
        forward = !flow.regen,
        on =
            (flow.driveActive && !flow.engineActive) || flow.regen,
    )
    link(2, pal.gas, forward = false, on = flow.engineActive)
    val nodes =
        listOf(
            Triple("Grid", flow.gridValue, pal.ev to flow.gridActive),
            Triple("Battery", flow.batteryValue, battColor to flow.batteryActive),
            Triple("Drive unit", flow.driveValue, driveColor to flow.driveActive),
            Triple("Engine", flow.engineValue, pal.gas to flow.engineActive),
        )
    nodes.forEachIndexed { i, (label, value, colorOn) ->
        val (color, active) = colorOn
        val c = Offset(NODE_X[i] * k, y)
        drawCircle(pal.surface2, NODE_R * k, c)
        if (active) drawCircle(color.copy(alpha = 0.12f), NODE_R * k, c)
        drawCircle(
            if (active) color else pal.line2,
            NODE_R * k,
            c,
            style = Stroke(width = (if (active) 2f else 1f) * k),
        )
        val iconPx = ICON * k
        translate(c.x - iconPx / 2, c.y - iconPx / 2) {
            with(
                icons[i],
            ) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(if (active) color else pal.faint)) }
        }
        centeredText(
            measurer,
            label,
            Offset(c.x, (NODE_Y + LABEL_DY) * k),
            TextStyle(
                fontFamily = VoltFonts.hanken,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = pal.text,
            ),
        )
        centeredText(
            measurer,
            value,
            Offset(c.x, (NODE_Y + VALUE_DY) * k),
            TextStyle(
                fontFamily = VoltFonts.barlow,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                color = if (active) color else pal.faint,
            ),
        )
    }
}

private fun DrawScope.centeredText(
    measurer: TextMeasurer,
    text: String,
    baseline: Offset,
    style: TextStyle,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(baseline.x - layout.size.width / 2f, baseline.y - layout.firstBaseline))
}

private const val DASH_PERIOD = 11f
private const val FLOW_MS = 420
private const val ICON = 24f
private const val LABEL_DY = 50f
private const val VALUE_DY = 68f
