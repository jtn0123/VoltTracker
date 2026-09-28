package com.volttracker.obdpoc.ui.drive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltPill
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Mockup geometry (drive.js arcGauge), in dp: a 380 × 350 box, ring centre (190, 188), r 152.
private const val BOX_W = 380f
private const val BOX_H = 350f
private const val CX = 190f
private const val CY = 188f
private const val R = 152f
private const val RING = 16f
private const val ENGINE_RING_GAP = 17f
private const val ENGINE_RING = 3f

/** Ring color for the moment's power: regen EV green, engine amber, drive Volt teal. */
fun powerColor(
    pal: VoltPalette,
    role: PowerRole,
): Color =
    when (role) {
        PowerRole.REGEN -> pal.ev
        PowerRole.GAS -> pal.gas
        PowerRole.DRIVE -> pal.volt
        PowerRole.IDLE -> pal.muted
    }

/**
 * Direction A's ring (mockups `arcGauge`). Driving it is a power/regen gauge — teal drive,
 * green regen, amber with the engine on — plus a thin rpm ring; parked it is the battery
 * gauge; charging it fills toward full with a moving shimmer.
 */
@Composable
fun ArcGauge(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    val measurer = rememberTextMeasurer()
    val shimmer by rememberInfiniteTransition(label = "charge-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = SHIMMER_PERIOD,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    Box(
        modifier =
            modifier
                .padding(top = 14.dp, bottom = 12.dp)
                .size(BOX_W.dp, BOX_H.dp)
                .semantics(mergeDescendants = true) {},
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
        ) {
            val g = Geo(size.width / BOX_W)
            drawTrack(g, pal)
            if (state.phase == DrivePhase.DRIVE) {
                drawPowerRing(g, pal, state, measurer)
            } else {
                drawSocRing(g, pal, state, measurer, if (state.phase == DrivePhase.CHARGING) shimmer else null)
            }
        }
        GaugeCenter(state, Modifier.fillMaxWidth().padding(top = 78.dp))
        if (state.phase != DrivePhase.CHARGING) {
            Gears(
                gear = state.gear,
                // `.a-gear` sits 252 px into `.a-center`, which itself starts 78 px down.
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 330.dp),
            )
        }
    }
}

/** Mockup-unit → pixel helper for the ring. */
private class Geo(
    val k: Float,
) {
    fun px(v: Float): Float = v * k

    fun polar(
        r: Float,
        deg: Float,
    ): Offset {
        val a = Math.toRadians((deg - 90f).toDouble())
        return Offset(px(CX + r * cos(a).toFloat()), px(CY + r * sin(a).toFloat()))
    }
}

private fun DrawScope.arc(
    g: Geo,
    r: Float,
    fromDeg: Float,
    toDeg: Float,
    color: Color,
    width: Float,
    pathEffect: PathEffect? = null,
) {
    val sweep = (toDeg - fromDeg).let { if (abs(it) < MIN_SWEEP) MIN_SWEEP else it }
    drawArc(
        color = color,
        startAngle = fromDeg - 90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(g.px(CX - r), g.px(CY - r)),
        size = Size(g.px(2 * r), g.px(2 * r)),
        style = Stroke(width = g.px(width), cap = StrokeCap.Round, pathEffect = pathEffect),
    )
}

/** Value arc with a soft halo (the mockup's blurred copy, approximated with wider faint strokes). */
private fun DrawScope.glowArc(
    g: Geo,
    fromDeg: Float,
    toDeg: Float,
    color: Color,
    haloAlpha: Float,
) {
    arc(g, R, fromDeg, toDeg, color.copy(alpha = haloAlpha * HALO_OUTER), RING + HALO_OUTER_W)
    arc(g, R, fromDeg, toDeg, color.copy(alpha = haloAlpha * HALO_INNER), RING + HALO_INNER_W)
    arc(g, R, fromDeg, toDeg, color, RING)
}

private fun DrawScope.knob(
    g: Geo,
    deg: Float,
    pal: VoltPalette,
) {
    drawCircle(pal.bg, radius = g.px(KNOB_R), center = g.polar(R, deg))
}

private fun DrawScope.drawTrack(
    g: Geo,
    pal: VoltPalette,
) {
    arc(g, R, ArcGeometry.START_DEG, ArcGeometry.END_DEG, pal.track, RING)
}

private fun DrawScope.tick(
    g: Geo,
    deg: Float,
    r0: Float,
    r1: Float,
    color: Color,
    width: Float,
) {
    drawLine(color, g.polar(r0, deg), g.polar(r1, deg), strokeWidth = g.px(width), cap = StrokeCap.Round)
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    style: TextStyle,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun tickStyle(
    color: Color,
    size: Float = 11f,
) = TextStyle(
    fontFamily = VoltFonts.barlowSemiCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    letterSpacing = 0.08.em,
    color = color,
)

private fun DrawScope.drawPowerRing(
    g: Geo,
    pal: VoltPalette,
    state: DriveUiState,
    measurer: TextMeasurer,
) {
    // Regen zone tint.
    arc(g, R, ArcGeometry.START_DEG, ArcGeometry.ZERO_DEG, pal.ev.copy(alpha = REGEN_TINT), RING)
    for (kw in -60..120 step 10) {
        val d = ArcGeometry.kwToDeg(kw.toDouble())
        val major = kw % 50 == 0 || kw == -60 || kw == 120
        if (abs(d) < TICK_CLEAR_DEG) continue
        tick(
            g,
            d,
            R - RING,
            R - if (major) 25f else 21f,
            pal.faint.copy(alpha = if (major) 0.9f else 0.45f),
            if (major) 1.6f else 1f,
        )
    }
    label(measurer, "0", g.polar(R - 40f, ArcGeometry.kwToDeg(0.0) + 3f), tickStyle(pal.faint))
    label(measurer, "100", g.polar(R - 40f, ArcGeometry.kwToDeg(100.0) - 3f), tickStyle(pal.faint))
    val regenAt = g.polar(R - 42f, ArcGeometry.kwToDeg(-36.0))
    label(measurer, "REGEN", Offset(regenAt.x + g.px(4f), regenAt.y), tickStyle(pal.ev.copy(alpha = 0.8f), 10f))

    val a = ArcGeometry.kwToDeg(state.powerKw)
    val color = powerColor(pal, state.powerRole)
    if (state.powerKw >= 0) {
        glowArc(g, ArcGeometry.ZERO_DEG, a, color, GLOW_DRIVE)
    } else {
        glowArc(g, a, ArcGeometry.ZERO_DEG, color, GLOW_DRIVE)
    }
    knob(g, a, pal)
    // Zero marker across the ring.
    drawLine(
        pal.text.copy(alpha = 0.55f),
        g.polar(R + 12f, ArcGeometry.ZERO_DEG),
        g.polar(R - 12f, ArcGeometry.ZERO_DEG),
        strokeWidth = g.px(2f),
        cap = StrokeCap.Round,
    )
    // Engine ring: always drawn as a faint track, filled amber by rpm while the engine runs.
    val rr = R + ENGINE_RING_GAP
    val gas = state.mode == DriveMode.GAS
    arc(
        g,
        rr,
        ArcGeometry.START_DEG,
        ArcGeometry.END_DEG,
        pal.track.copy(
            alpha =
                pal.track.alpha * if (gas) 1f else 0.5f,
        ),
        ENGINE_RING,
    )
    if (gas && state.rpm > 0) {
        arc(g, rr, ArcGeometry.START_DEG, ArcGeometry.START_DEG + ArcGeometry.rpmSweep(state.rpm), pal.gas, ENGINE_RING)
    }
}

private fun DrawScope.drawSocRing(
    g: Geo,
    pal: VoltPalette,
    state: DriveUiState,
    measurer: TextMeasurer,
    shimmerPhase: Float?,
) {
    for (p in 0..100 step 25) {
        tick(g, ArcGeometry.socToDeg(p.toDouble()), R - RING, R - 24f, pal.faint.copy(alpha = 0.8f), 1.4f)
    }
    if (!state.connected) {
        label(measurer, "E", g.polar(R + 2f, ArcGeometry.START_DEG - 9f), tickStyle(pal.faint))
        label(measurer, "F", g.polar(R + 2f, ArcGeometry.END_DEG + 9f), tickStyle(pal.faint))
        return
    }
    val soc = state.shownSocPercent
    val a = ArcGeometry.socToDeg(soc)
    val from = state.chargeFromSoc
    if (state.phase == DrivePhase.CHARGING) {
        arc(g, R, ArcGeometry.socToDeg(from ?: soc), ArcGeometry.END_DEG, pal.ev.copy(alpha = CHARGE_TARGET_TINT), RING)
    }
    glowArc(g, ArcGeometry.START_DEG, a, pal.ev, GLOW_SOC)
    if (shimmerPhase != null) {
        arc(
            g,
            R,
            ArcGeometry.START_DEG,
            a,
            pal.evBright.copy(alpha = SHIMMER_ALPHA),
            RING,
            PathEffect.dashPathEffect(floatArrayOf(g.px(2f), g.px(SHIMMER_PERIOD - 2f)), -g.px(shimmerPhase)),
        )
        if (from !=
            null
        ) {
            drawCircle(
                pal.bg.copy(alpha = 0.7f),
                radius = g.px(3f),
                center = g.polar(R, ArcGeometry.socToDeg(from)),
            )
        }
    }
    knob(g, a, pal)
    label(measurer, "E", g.polar(R + 2f, ArcGeometry.START_DEG - 9f), tickStyle(pal.faint))
    label(measurer, "F", g.polar(R + 2f, ArcGeometry.END_DEG + 9f), tickStyle(pal.faint))
}

/** The mode pill for the moment (mockups `modePill`). */
@Composable
fun ModePill(state: DriveUiState) {
    when {
        !state.connected -> VoltPill("Not connected", PillTone.NEUTRAL)
        state.phase == DrivePhase.CHARGING ->
            VoltPill(listOfNotNull("Charging", state.chargeLevel).joinToString(" · "), PillTone.EV)
        state.phase == DrivePhase.PARKED ->
            when (state.locked) {
                true -> VoltPill("Parked · Locked", PillTone.NEUTRAL, icon = VoltIcons.Lock)
                false -> VoltPill("Parked · Unlocked", PillTone.NEUTRAL, dot = false)
                null -> VoltPill("Parked", PillTone.NEUTRAL, dot = false)
            }
        state.mode == DriveMode.GAS -> VoltPill("Gas · Range extender", PillTone.GAS)
        else -> VoltPill("Electric", PillTone.EV)
    }
}

private val speedStyle =
    TextStyle(
        fontFamily = VoltFonts.barlow,
        fontWeight = FontWeight.Light,
        fontSize = 120.sp,
        letterSpacing = (-0.04).em,
        fontFeatureSettings = "tnum",
    )

/**
 * CSS `line-height: .9`: the number occupies a [lineBox]-tall slot with its glyphs centred in it
 * and free to overflow, so the lines below sit where the mockups put them.
 */
@Composable
private fun HeroNumber(
    text: AnnotatedString,
    style: TextStyle,
    lineBox: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.height(lineBox), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = style,
            color = VoltColors.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentHeight(unbounded = true),
        )
    }
}

@Composable
private fun GaugeCenter(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(30.dp)) { ModePill(state) }
        when {
            state.phase == DrivePhase.DRIVE && state.connected -> DriveCenter(state, pal)
            state.phase == DrivePhase.CHARGING && state.connected -> ChargeCenter(state)
            else -> ParkedCenter(state)
        }
    }
}

@Composable
private fun DriveCenter(
    state: DriveUiState,
    pal: VoltPalette,
) {
    val color = powerColor(pal, state.powerRole)
    Spacer(Modifier.height(10.dp))
    HeroNumber(
        text = AnnotatedString("${state.speedMph}"),
        style = speedStyle,
        lineBox = SPEED_LINE_BOX,
        modifier = Modifier.semantics { contentDescription = "${state.speedMph} miles per hour" },
    )
    Text(
        text = "mph",
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text =
            buildAnnotatedString {
                append((if (state.regenerating) "−" else "") + oneDecimal(abs(state.powerKw)))
                withStyle(SpanStyle(fontFamily = VoltFonts.hanken, fontSize = 13.sp, color = pal.muted)) {
                    append(if (state.regenerating) " kW regen" else " kW power")
                }
            },
        style = VoltType.value,
        color = color,
    )
    if (state.mode == DriveMode.GAS && state.rpm > 0) {
        Text(
            text = "Engine ${String.format(Locale.US, "%,d", state.rpm)} rpm",
            style =
                VoltType.body.copy(
                    fontFamily = VoltFonts.barlow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
            color = pal.gas,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val socStyle = speedStyle.copy(fontSize = 104.sp)

@Composable
private fun SocNumber(text: String) {
    HeroNumber(
        text =
            buildAnnotatedString {
                append(text)
                if (text != "--") {
                    withStyle(
                        SpanStyle(
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Normal,
                            color = VoltColors.textSecondary,
                            letterSpacing = 0.em,
                        ),
                    ) { append(" %") }
                }
            },
        style = socStyle,
        lineBox = SOC_LINE_BOX,
        modifier = Modifier.padding(top = 18.dp),
    )
}

@Composable
private fun ParkedCenter(state: DriveUiState) {
    if (!state.connected) {
        SocNumber("--")
        Text(
            text = "Connect to see your Volt live",
            style = VoltType.heroUnit,
            color = VoltColors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    SocNumber("${state.shownSocPercent.toInt()}")
    Text(
        text = state.evRangeMiles?.let { "${it.toInt()} mi electric range" } ?: "Electric range not reported",
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ChargeCenter(state: DriveUiState) {
    SocNumber("${state.shownSocPercent.toInt()}")
    val text = VoltColors.textPrimary
    Text(
        text =
            buildAnnotatedString {
                when (val eta = state.chargeEta) {
                    is ChargeEta.Finish -> {
                        append("Full by ")
                        withStyle(SpanStyle(color = text, fontWeight = FontWeight.SemiBold)) {
                            append(clockLabel(state.sampleAtMs, eta.remainingMs))
                        }
                        append(" · ${shortDurationLabel(eta.remainingMs)}")
                    }
                    ChargeEta.NearlyFull -> append("Topping off — nearly full")
                    ChargeEta.Estimating, null -> append("Estimating time to full…")
                }
            },
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    val parts =
        listOfNotNull(
            state.chargeAcVolts?.let { "${it.toInt()} V" },
            state.chargeAcAmps?.let { "${it.toInt()} A" },
        )
    Text(
        text =
            buildAnnotatedString {
                append("+" + oneDecimal(state.chargeKw))
                withStyle(
                    SpanStyle(fontFamily = VoltFonts.hanken, fontSize = 13.sp, color = VoltColors.textSecondary),
                ) {
                    append((listOf(" kW") + parts).joinToString(" · "))
                }
            },
        style = VoltType.value,
        color = VoltColors.energy,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** PRNDL with the current gear lit (mockups `.gears`). */
@Composable
fun Gears(
    gear: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Gear $gear" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        "PRNDL".forEach { letter ->
            val on = gear == letter.toString()
            Box(
                modifier =
                    Modifier
                        .size(24.dp, 26.dp)
                        .background(
                            if (on) VoltColors.surfaceElevated else Color.Transparent,
                            VoltShapes.inner,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter.toString(),
                    style =
                        TextStyle(
                            fontFamily = VoltFonts.barlow,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                    color = if (on) VoltColors.textPrimary else VoltColors.textTertiary,
                )
            }
        }
    }
}

private val SPEED_LINE_BOX = 108.dp
private val SOC_LINE_BOX = 94.dp
private const val MIN_SWEEP = 0.01f
private const val KNOB_R = 5f
private const val TICK_CLEAR_DEG = 50f
private const val REGEN_TINT = 0.10f
private const val CHARGE_TARGET_TINT = 0.14f
private const val GLOW_DRIVE = 0.55f
private const val GLOW_SOC = 0.45f
private const val HALO_OUTER = 0.14f
private const val HALO_INNER = 0.3f
private const val HALO_OUTER_W = 14f
private const val HALO_INNER_W = 6f
private const val SHIMMER_PERIOD = 60f
private const val SHIMMER_MS = 1600
private const val SHIMMER_ALPHA = 0.6f
