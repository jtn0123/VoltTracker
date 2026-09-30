package com.volttracker.obdpoc.ui.diag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.ButtonStyle
import com.volttracker.obdpoc.ui.components.CellHistogram
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.MIN_CELLS_FOR_HISTOGRAM
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltFade
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltListRow
import com.volttracker.obdpoc.ui.components.VoltPill
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.rememberLoopPhase
import com.volttracker.obdpoc.ui.components.voltAnimateSize
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Car › Health (mockups `S.health` / `S.health?fault=1`): the trouble codes in plain words with
 * whether it's safe to drive, scanning and sharing, the HV battery's health, and the ways into
 * live signals, freeze frames, the adapter and the troubleshooter.
 */
@Composable
fun DiagScreen(
    state: DiagUiState,
    modifier: Modifier = Modifier,
    battery: HvBattery = hvBattery(DriveUiState(), null, null),
    drive: DriveUiState = DriveUiState(),
    demo: Boolean = false,
    onBack: () -> Unit = {},
    actions: HealthActions = HealthActions(),
) {
    VoltScreen(
        title = "Health",
        subtitle = state.statusLine(drive),
        dot = connectionDot(state.connected, state.connecting),
        onBack = onBack,
        modifier = modifier,
        statusSubtitle = true,
        onRefresh = LocalVoltNav.current.refresh,
    ) {
        Hero(state, demo, actions) { actions.onShare(healthReport(state, battery, demo)) }
        Spacer(Modifier.height(12.dp))
        BatteryCard(battery)
        Spacer(Modifier.height(12.dp))
        VoltListCard {
            VoltListRow(
                VoltIcons.Pulse,
                "Live signals",
                subtitle = liveSignalsLine(drive),
                onClick = actions.onOpenSignals,
            )
            VoltListDivider()
            VoltListRow(
                VoltIcons.Doc,
                "Freeze frame",
                subtitle = state.freezeFrameLine(),
                // Nothing to open until the car has captured one.
                onClick = actions.onOpenFreezeFrame.takeIf { state.hasFreezeFrame() },
            )
            VoltListDivider()
            VoltListRow(
                VoltIcons.Bluetooth,
                "Adapter",
                subtitle = adapterLine(state.adapterLabel, state.connected),
                onClick = actions.onOpenAdapter,
            )
            VoltListDivider()
            VoltListRow(
                VoltIcons.Wrench,
                "Troubleshooter",
                subtitle = "Connection & data checks",
                onClick = actions.onOpenAdapter,
            )
        }
    }
}

@Composable
private fun Hero(
    state: DiagUiState,
    demo: Boolean,
    actions: HealthActions,
    onShare: () -> Unit,
) {
    val hero = state.hero()
    val codes = state.codes.orEmpty()
    val busy = state.busyLabel != null
    ScanDoneHaptic(busy)
    // The card eases taller when a scan finds codes, and its verdict cross-fades to the new one.
    Column(
        Modifier
            .fillMaxWidth()
            .voltCard()
            .voltAnimateSize()
            .padding(16.dp),
    ) {
        VoltFade(hero, label = "health-hero") { shown ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconSquare(heroIcon(shown.tone), tone = shown.tone, size = 48.dp, iconSize = 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.title,
                        style = VoltType.bodyStrong.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = VoltColors.textPrimary,
                    )
                    Text(shown.subtitle, style = VoltType.caption, color = VoltColors.textSecondary)
                }
            }
        }
        ScanSweep(busy)
        codes.forEach { code ->
            HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp, modifier = Modifier.padding(top = 12.dp))
            DtcRow(code, state.nowMs)
        }
        safeToDrive(codes)?.let { Verdict(it) }
        state.earlierLine()?.let {
            Text(
                it,
                style = VoltType.caption,
                color = VoltColors.textTertiary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Buttons(codes.isNotEmpty(), busy, actions.onScan, onShare)
        HeroFootnote(state, demo, codes.isNotEmpty() && !busy, actions.onClear)
    }
}

/** A tick when a scan finishes, so the result is felt without watching the screen. */
@Composable
private fun ScanDoneHaptic(busy: Boolean) {
    val haptics = LocalHapticFeedback.current
    var wasBusy by remember { mutableStateOf(busy) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        wasBusy = busy
    }
}

/** While a scan runs: a thin line with a highlight sweeping across it, under the verdict. */
@Composable
private fun ScanSweep(busy: Boolean) {
    val accent = VoltColors.accent
    val track = VoltColors.track
    val phase = rememberLoopPhase(active = busy, period = 1f, durationMs = SWEEP_MS, label = "scan-sweep")
    AnimatedVisibility(visible = busy) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(3.dp)
                .clip(VoltShapes.chip)
                .background(track)
                .drawBehind {
                    // Steady and full under reduce-motion: busy, but nothing moving.
                    val p = phase ?: return@drawBehind drawRect(accent.copy(alpha = SWEEP_STILL_ALPHA))
                    val w = size.width * SWEEP_WIDTH
                    val x = (size.width + w) * p - w
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                },
        )
    }
}

private const val SWEEP_MS = 1_100
private const val SWEEP_WIDTH = 0.35f
private const val SWEEP_STILL_ALPHA = 0.5f

private fun heroIcon(tone: PillTone): ImageVector =
    when (tone) {
        PillTone.EV -> VoltIcons.Check
        PillTone.WARN, PillTone.BAD -> VoltIcons.Alert
        else -> VoltIcons.Scan
    }

@Composable
private fun DtcRow(
    code: DtcCode,
    nowMs: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            code.code,
            style = VoltType.value.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
            color = VoltColors.textPrimary,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(VoltColors.surfaceElevated)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        val pill = code.pill()
        // At Large/Largest text the pill beside the title squeezed it to a word per line; stack it instead.
        val stackPill = LocalDensity.current.fontScale > STACK_PILL_FONT_SCALE
        Column(Modifier.weight(1f)) {
            Text(code.title, style = VoltType.bodyStrong.copy(fontSize = 14.sp), color = VoltColors.textPrimary)
            Text(code.detailLine(nowMs), style = VoltType.caption, color = VoltColors.textSecondary)
            if (stackPill) {
                Spacer(Modifier.height(6.dp))
                VoltPill(pill.text, pill.tone, dot = false, small = true)
            }
        }
        if (!stackPill) VoltPill(pill.text, pill.tone, dot = false, small = true)
    }
}

@Composable
private fun Verdict(line: HealthLine) {
    val safe = line.tone == PillTone.EV
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VoltColors.surfaceElevated)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (safe) VoltIcons.Check else VoltIcons.Alert,
            contentDescription = null,
            tint = pillColor(line.tone),
            modifier = Modifier.size(16.dp),
        )
        Text(
            line.text,
            style = VoltType.caption.copy(fontSize = 12.5.sp),
            color = if (safe) VoltColors.textSecondary else VoltColors.alert,
        )
    }
}

@Composable
private fun Buttons(
    hasCodes: Boolean,
    busy: Boolean,
    onScan: () -> Unit,
    onShare: () -> Unit,
) {
    val scanLabel =
        when {
            busy -> "Scanning…"
            hasCodes -> "Scan again"
            else -> "Scan now"
        }
    val scan = if (busy) ({}) else onScan
    val scanModifier = Modifier.alpha(if (busy) BUSY_ALPHA else 1f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoltButton(
            text = scanLabel,
            accent = true,
            icon = VoltIcons.Scan,
            modifier = scanModifier.weight(1f),
            onClick = scan,
        )
        if (hasCodes) {
            VoltButton(
                text = "Share report",
                style = ButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
                onClick = onShare,
            )
        }
    }
}

/** What a scan does to a live session, and the way to clear codes (the host confirms first). */
@Composable
private fun ColumnScope.HeroFootnote(
    state: DiagUiState,
    demo: Boolean,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    if (state.connected && !demo && state.busyLabel == null) {
        Text(
            "A scan ends the live connection; reconnect from Drive after.",
            style = VoltType.caption,
            color = VoltColors.textTertiary,
            modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally),
        )
    }
    if (canClear) {
        Text(
            "Clear codes…",
            style = VoltType.caption,
            color = VoltColors.textTertiary,
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp)
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BatteryCard(battery: HvBattery) {
    Column(Modifier.fillMaxWidth().voltCard().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltLabel("HV battery")
            Text("${battery.groups} cell groups", style = VoltType.caption, color = VoltColors.textSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BatteryFigure(battery.sohPct?.roundToInt()?.toString(), "%", "capacity health", big = true)
            BatteryFigure(
                battery.capacityAh?.let { String.format(Locale.US, "%.1f", it) },
                " Ah",
                "of ${PACK_NEW_AH.roundToInt()} Ah new",
            )
            BatteryFigure(battery.spreadMv?.roundToInt()?.toString(), " mV", "cell spread")
        }
        val weakest = battery.weakestLabel()
        if (battery.cells.count { it != null } >= MIN_CELLS_FOR_HISTOGRAM) {
            CellHistogram(battery.cells, battery.weakestCell, Modifier.fillMaxWidth().height(46.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Cell 1", style = VoltType.caption, color = VoltColors.textSecondary)
                weakest?.let { Text(it, style = VoltType.caption, color = VoltColors.warn) }
                Text("Cell ${battery.cells.size}", style = VoltType.caption, color = VoltColors.textSecondary)
            }
        } else {
            val note =
                when {
                    !battery.reported -> "Appears after the car reports a battery read"
                    weakest != null -> "Lowest cell $weakest · per-cell detail after a full cell read"
                    else -> "Per-cell detail appears after a full cell read"
                }
            Text(note, style = VoltType.caption, color = VoltColors.textTertiary)
        }
    }
}

@Composable
private fun BatteryFigure(
    value: String?,
    unit: String,
    caption: String,
    big: Boolean = false,
) {
    Column {
        Text(
            buildAnnotatedString {
                append(value ?: DASH)
                if (value != null) {
                    withStyle(
                        SpanStyle(fontSize = 14.sp, color = VoltColors.textSecondary, fontWeight = FontWeight.Normal),
                    ) {
                        append(unit)
                    }
                }
            },
            style =
                if (big) {
                    VoltType.value.copy(fontSize = 40.sp, fontWeight = FontWeight.Light, lineHeight = 40.sp)
                } else {
                    VoltType.value.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium)
                },
            color = VoltColors.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = if (value == null) Modifier.semantics { contentDescription = NOT_REPORTED } else Modifier,
        )
        Text(caption, style = VoltType.caption, color = VoltColors.textSecondary)
    }
}

private const val BUSY_ALPHA = 0.5f

@Preview(widthDp = 412, heightDp = 1300)
@Composable
private fun DiagScreenPreview() {
    VoltTheme {
        DiagScreen(
            DiagUiState.demo,
            battery = hvBattery(DriveUiState.demoParked, 91.0, 47.3),
            drive = DriveUiState.demoParked,
        )
    }
}

private const val STACK_PILL_FONT_SCALE = 1.1f
