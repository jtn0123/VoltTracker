package com.volttracker.obdpoc.ui.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import com.volttracker.obdpoc.ui.charge.levelName
import com.volttracker.obdpoc.ui.components.CappedTextScale
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltMotion
import com.volttracker.obdpoc.ui.components.VoltPill
import com.volttracker.obdpoc.ui.components.announceChanges
import com.volttracker.obdpoc.ui.components.glideState
import com.volttracker.obdpoc.ui.components.glideTenths
import com.volttracker.obdpoc.ui.components.glideWhole
import com.volttracker.obdpoc.ui.components.rememberLoopPhase
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
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
private const val CENTER_TOP = 78f
private const val GEAR_TOP = 330f

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
    val shimmer =
        rememberLoopPhase(
            active = state.connected && state.phase == DrivePhase.CHARGING,
            period = SHIMMER_PERIOD,
            durationMs = SHIMMER_MS,
            label = "charge-shimmer",
        )
    // The ring eases to each new reading instead of jumping a poll at a time. Read while drawing,
    // so a glide repaints the canvas without recomposing the screen.
    val power = glideState(state.powerKw.toFloat(), "gauge-power")
    val soc = glideState(state.shownSocPercent.toFloat(), "gauge-soc")
    val rpm = glideState(state.rpm.toFloat(), "gauge-rpm")
    // The mockups' 380 × 350 box, scaled down to fit a narrower phone: every offset below follows
    // the ring, so the centre text and the gear row stay where they belong on a 360dp screen.
    BoxWithConstraints(
        modifier =
            modifier
                .padding(top = 14.dp, bottom = 12.dp)
                .widthIn(max = BOX_W.dp)
                .fillMaxWidth()
                .aspectRatio(BOX_W / BOX_H),
    ) {
        val k = maxWidth.value / BOX_W
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
        ) {
            val g = Geo(size.width / BOX_W)
            drawTrack(g, pal)
            if (state.phase == DrivePhase.DRIVE) {
                drawPowerRing(g, pal, state, measurer, power.value.toDouble(), rpm.value.roundToInt())
            } else {
                val shimmerPhase = if (state.phase == DrivePhase.CHARGING) shimmer ?: 0f else null
                drawSocRing(g, pal, state, measurer, soc.value.toDouble(), shimmerPhase)
            }
        }
        CappedTextScale {
            GaugeCenter(state, k, Modifier.fillMaxWidth().padding(top = (CENTER_TOP * k).dp))
        }
        if (state.phase != DrivePhase.CHARGING) {
            // The letters sit in fixed 24 × 26dp cells under the ring, so their text scale is capped too.
            CappedTextScale {
                Gears(
                    gear = state.gear,
                    // `.a-gear` sits 252 px into `.a-center`, which itself starts 78 px down.
                    // An offset, not top padding: padding would leave the row only the last few
                    // dp of the box, clipping the letters at larger text sizes.
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (GEAR_TOP * k).dp),
                )
            }
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
    powerKw: Double,
    rpm: Int,
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
    // "100 kW", not "100": the driving ring is a power gauge and must not read as a speedometer.
    label(measurer, "100 kW", g.polar(R - 44f, ArcGeometry.kwToDeg(100.0) - 5f), tickStyle(pal.faint))
    val regenAt = g.polar(R - 42f, ArcGeometry.kwToDeg(-36.0))
    label(measurer, "REGEN", Offset(regenAt.x + g.px(4f), regenAt.y), tickStyle(pal.ev.copy(alpha = 0.8f), 10f))

    val a = ArcGeometry.kwToDeg(powerKw)
    val color = powerColor(pal, state.powerRole)
    if (powerKw >= 0) {
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
    if (gas && rpm > 0) {
        arc(g, rr, ArcGeometry.START_DEG, ArcGeometry.START_DEG + ArcGeometry.rpmSweep(rpm), pal.gas, ENGINE_RING)
    }
}

private fun DrawScope.drawSocRing(
    g: Geo,
    pal: VoltPalette,
    state: DriveUiState,
    measurer: TextMeasurer,
    soc: Double,
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
    val a = ArcGeometry.socToDeg(soc)
    val from = state.chargeFromSoc
    if (state.phase == DrivePhase.CHARGING) {
        // The stretch still to charge, up to the Settings charge target (not always 100%).
        val target = ArcGeometry.socToDeg(state.chargeTargetPct.toDouble())
        arc(g, R, ArcGeometry.socToDeg(from ?: soc), target, pal.ev.copy(alpha = CHARGE_TARGET_TINT), RING)
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

/** What the mode pill says for the moment. */
internal data class ModePillSpec(
    val text: String,
    val tone: PillTone,
    val dot: Boolean = true,
    val icon: ImageVector? = null,
)

internal fun modePill(state: DriveUiState): ModePillSpec =
    when {
        !state.connected -> ModePillSpec("Not connected", PillTone.NEUTRAL)
        state.phase == DrivePhase.CHARGING ->
            ModePillSpec(listOfNotNull("Charging", levelName(state.chargeLevel)).joinToString(" · "), PillTone.EV)
        state.phase == DrivePhase.PARKED ->
            when (state.locked) {
                true -> ModePillSpec("Parked · Locked", PillTone.NEUTRAL, icon = VoltIcons.Lock)
                false -> ModePillSpec("Parked · Unlocked", PillTone.NEUTRAL, dot = false)
                null -> ModePillSpec("Parked", PillTone.NEUTRAL, dot = false)
            }
        state.mode == DriveMode.GAS -> ModePillSpec("Gas · Range extender", PillTone.GAS)
        else -> ModePillSpec("Electric", PillTone.EV)
    }

/** The mode pill for the moment (mockups `modePill`); a change of mode fades the new pill in. */
@Composable
fun ModePill(state: DriveUiState) {
    val reduceMotion = LocalVoltPrefs.current.reduceMotion
    AnimatedContent(
        targetState = modePill(state),
        transitionSpec = {
            fadeIn(VoltMotion.spec(VoltMotion.STANDARD_MS, reduceMotion)) +
                scaleIn(
                    VoltMotion.spec(VoltMotion.STANDARD_MS, reduceMotion),
                    initialScale = PILL_IN_SCALE,
                ) togetherWith
                fadeOut(VoltMotion.spec(VoltMotion.FAST_MS, reduceMotion))
        },
        contentAlignment = Alignment.Center,
        label = "mode-pill",
    ) { pill ->
        VoltPill(pill.text, pill.tone, dot = pill.dot, icon = pill.icon)
    }
}

private const val PILL_IN_SCALE = 0.92f

/** Which set of figures sits inside the ring. */
private enum class CenterKind { DRIVE, CHARGE, PARKED }

private fun centerKind(state: DriveUiState): CenterKind =
    when {
        state.phase == DrivePhase.DRIVE && state.connected -> CenterKind.DRIVE
        state.phase == DrivePhase.CHARGING && state.connected -> CenterKind.CHARGE
        else -> CenterKind.PARKED
    }

/** The hero figures are sized to the ring in dp, not sp: the ring doesn't grow with the text size. */
@Composable
private fun heroStyle(
    sizeDp: Float,
    k: Float,
): TextStyle {
    val size = with(LocalDensity.current) { (sizeDp * k).dp.toSp() }
    return VoltType.display.copy(fontSize = size, lineHeight = size)
}

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
    k: Float,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    val prefs = LocalVoltPrefs.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // The mode is announced on its own when it changes; the figures below read as one line.
        Box(Modifier.heightIn(min = 30.dp).announceChanges(prefs)) { ModePill(state) }
        val described = gaugeDescription(state, prefs.clock24h)
        // Pulling away, parking or plugging in cross-fades the figures instead of swapping them.
        AnimatedContent(
            targetState = centerKind(state),
            transitionSpec = {
                // Unclipped: the figures are laid out to overflow their line boxes, and a height
                // change mid-fade must not slice the line underneath.
                fadeIn(VoltMotion.spec(VoltMotion.STANDARD_MS, prefs.reduceMotion)) togetherWith
                    fadeOut(VoltMotion.spec(VoltMotion.FAST_MS, prefs.reduceMotion)) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.clearAndSetSemantics { contentDescription = described },
            label = "gauge-center",
        ) { kind ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (kind) {
                    CenterKind.DRIVE -> DriveCenter(state, pal, k)
                    CenterKind.CHARGE -> ChargeCenter(state, k, prefs.clock24h)
                    CenterKind.PARKED -> ParkedCenter(state, k)
                }
            }
        }
    }
}

@Composable
private fun DriveCenter(
    state: DriveUiState,
    pal: VoltPalette,
    k: Float,
) {
    val color = powerColor(pal, state.powerRole)
    val units = state.units
    val speed = glideWhole(units.speed(state.speedMph.toDouble()), "gauge-speed")
    val shownKw = glideTenths(state.powerKw, "gauge-power-text")
    val regen = state.regenerating && shownKw < 0
    Spacer(Modifier.height(10.dp))
    HeroNumber(
        text = AnnotatedString("$speed"),
        style = heroStyle(SPEED_DP, k),
        lineBox = (SPEED_LINE_BOX * k).dp,
    )
    Text(
        text = units.speedUnit,
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text =
            buildAnnotatedString {
                append((if (regen) "−" else "") + oneDecimal(abs(shownKw)))
                withStyle(SpanStyle(fontFamily = VoltFonts.hanken, fontSize = 13.sp, color = pal.muted)) {
                    append(if (regen) " kW regen" else " kW power")
                }
            },
        style = VoltType.value,
        color = color,
    )
    if (state.mode == DriveMode.GAS && state.rpm > 0) {
        Text(
            text = "Engine ${String.format(Locale.US, "%,d", glideWhole(state.rpm, "gauge-rpm-text"))} rpm",
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

@Composable
private fun SocNumber(
    text: String,
    k: Float,
) {
    HeroNumber(
        text =
            buildAnnotatedString {
                append(text)
                if (text != DASH) {
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
        style = heroStyle(SOC_DP, k),
        lineBox = (SOC_LINE_BOX * k).dp,
        modifier = Modifier.padding(top = (SOC_TOP * k).dp),
    )
}

@Composable
private fun ParkedCenter(
    state: DriveUiState,
    k: Float,
) {
    if (!state.connected) {
        SocNumber(DASH, k)
        Text(
            text = "Connect to see your Volt live",
            style = VoltType.heroUnit,
            color = VoltColors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    SocNumber("${glideWhole(state.shownSocPercent.toInt(), "gauge-soc-text")}", k)
    Text(
        text =
            state.evRangeMiles?.let { "${state.units.distanceWhole(it)} ${state.units.distanceUnit} electric range" }
                ?: "Electric range not reported",
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ChargeCenter(
    state: DriveUiState,
    k: Float,
    h24: Boolean,
) {
    SocNumber("${glideWhole(state.shownSocPercent.toInt(), "gauge-soc-text")}", k)
    val text = VoltColors.textPrimary
    Text(
        text =
            buildAnnotatedString {
                when (val eta = state.chargeEta) {
                    is ChargeEta.Finish -> {
                        append(etaLead(state.chargeTargetPct))
                        withStyle(SpanStyle(color = text, fontWeight = FontWeight.SemiBold)) {
                            append(clockLabel(state.sampleAtMs, eta.remainingMs, h24 = h24))
                        }
                        append(" · ${durationLabel(eta.remainingMs)}")
                    }
                    ChargeEta.NearlyFull -> append(NEARLY_FULL_TEXT)
                    ChargeEta.Estimating, null -> append(estimatingText(state.chargeTargetPct))
                }
            },
        style = VoltType.heroUnit,
        color = VoltColors.textSecondary,
        // Inside the ring: one line that shrinks to fit rather than running into the arc.
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = LINE_MIN_SP.sp, maxFontSize = VoltType.heroUnit.fontSize),
        modifier = Modifier.padding(top = 4.dp).widthIn(max = (INNER_LINE_W * k).dp),
    )
    val parts =
        listOfNotNull(
            state.chargeAcVolts?.let { "${it.toInt()} V" },
            state.chargeAcAmps?.let { "${it.toInt()} A" },
        )
    val chargeKw = glideTenths(state.chargeKw, "gauge-charge-kw")
    Text(
        text =
            buildAnnotatedString {
                append("+" + oneDecimal(chargeKw))
                withStyle(
                    SpanStyle(fontFamily = VoltFonts.hanken, fontSize = 13.sp, color = VoltColors.textSecondary),
                ) {
                    append((listOf(" kW") + parts).joinToString(" · "))
                }
            },
        style = VoltType.value,
        color = VoltColors.energy,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = LINE_MIN_SP.sp, maxFontSize = VoltType.value.fontSize),
        // The bottom line sits between the E and F marks, so it gets less room than the ETA line.
        modifier = Modifier.padding(top = 12.dp).widthIn(max = (BOTTOM_LINE_W * k).dp),
    )
}

/** How wide a line under the ring's big figure may run (mockup units) before it meets the arc. */
private const val INNER_LINE_W = 240f

/** How wide the charge-rate line may run: it sits lower, between the E and F marks. */
private const val BOTTOM_LINE_W = 200f

/** The smallest a ring line shrinks to. */
private const val LINE_MIN_SP = 10

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
                        .widthIn(min = 24.dp)
                        .heightIn(min = 26.dp)
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

private const val SPEED_DP = 120f
private const val SOC_DP = 104f
private const val SPEED_LINE_BOX = 108f
private const val SOC_LINE_BOX = 94f
private const val SOC_TOP = 18f
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
