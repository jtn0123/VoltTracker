package com.volttracker.obdpoc.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.NavBadge
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltListRow
import com.volttracker.obdpoc.ui.components.VoltPill
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.diag.summary
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.Meter
import com.volttracker.obdpoc.ui.drive.NumberUnit
import com.volttracker.obdpoc.ui.drive.cellBalanceText
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlin.math.roundToInt

/**
 * The Car tab (mockups `S.car`): the car from above with its tyres and lock, the car controls, the
 * 12 V battery, climate, tyres and windows, and the way into Health. Body readings come from the
 * SW-CAN broadcasts an OBDLink adapter hears; anything not heard reads "Not reported", anything
 * gone quiet says how long ago it was heard — never a guess.
 */
@Composable
fun CarScreen(
    drive: DriveUiState,
    car: CarUiState,
    diag: DiagUiState,
    /** HV battery capacity health (%), or null before the car reported it. */
    sohPct: Double?,
    demo: Boolean,
    modifier: Modifier = Modifier,
    actions: CarActions = CarActions(),
) {
    val nav = LocalVoltNav.current
    VoltScreen(
        title = "Car",
        subtitle = "Chevrolet Volt",
        dot = connectionDot(drive.connected),
        modifier = modifier,
    ) {
        StatusRow(drive, car)
        CarTopView(
            tires = drive.tires,
            locked = drive.locked,
            placardPsi = car.placardPsi,
            metric = car.metricUnits,
            modifier = Modifier.padding(top = 4.dp),
        )
        Controls(drive, car, demo, actions)
        TileRow(
            {
                val aux = aux12Tile(drive)
                Tile("12V battery", VoltIcons.Battery, aux, it, iconTone = aux.tone)
            },
            { Tile("Climate", VoltIcons.Fan, climateTile(drive, car), it) },
        )
        TileRow(
            {
                val tires = tiresTile(drive, car)
                val icon =
                    when {
                        drive.tires == null -> VoltIcons.Car
                        tires.tone == PillTone.NEUTRAL || tires.tone == PillTone.EV -> VoltIcons.Check
                        else -> VoltIcons.Alert
                    }
                Tile("Tires", icon, tires, it, iconTone = tires.tone)
            },
            { Tile("Windows", VoltIcons.Window, windowsTile(car), it) },
        )
        VoltListCard(Modifier.padding(top = 10.dp)) {
            VoltListRow(
                icon = VoltIcons.Pulse,
                title = "Vehicle health",
                subtitle = healthSummary(diag),
                tone = healthTone(diag),
                onClick = nav.openHealth,
            )
            VoltListDivider()
            VoltListRow(
                icon = VoltIcons.Cells,
                title = "HV battery",
                subtitle = batterySummary(sohPct, drive.cellSpreadMv),
                tone = PillTone.EV,
                onClick = nav.openHealth,
            )
            VoltListDivider()
            val canTest = drive.connected && !demo
            VoltListRow(
                icon = VoltIcons.Scan,
                title = "Body test",
                subtitle = bodyTestLine(canTest),
                onClick = actions.onBodyTest.takeIf { canTest },
            )
        }
    }
}

@Composable
private fun StatusRow(
    drive: DriveUiState,
    car: CarUiState,
) {
    val headline = carHeadline(drive, car)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VoltPill(headline.text, headline.tone)
        car.updatedLabel()?.let {
            Text(
                it,
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    drive: DriveUiState,
    car: CarUiState,
    demo: Boolean,
    actions: CarActions,
) {
    val open = car.controls.open(drive.connected, demo)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CarControl.entries.forEach { control ->
            ControlButton(
                icon = control.icon(),
                label = control.label(car),
                on = control.isOn(drive, car),
                enabled = open,
            ) { actions.onControl(control.command(car)) }
        }
    }
    val status = car.controls.statusLine(drive.connected, demo)
    val result = car.controls.lastResultLine()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ControlNote(status.text, status.tone)
        result?.let { ControlNote(it.text, it.tone) }
        when {
            demo -> Unit
            car.controls.enabled ->
                VoltButton(
                    text = "Turn off car controls",
                    modifier = Modifier.padding(top = 4.dp),
                    onClick = actions.onDisableControls,
                )
            else ->
                VoltButton(
                    text = "Turn on car controls",
                    modifier = Modifier.padding(top = 4.dp),
                    onClick = actions.onEnableControls,
                )
        }
    }
}

@Composable
private fun ControlNote(
    text: String,
    tone: PillTone,
) {
    Text(
        text = text,
        style = VoltType.caption,
        color = if (tone == PillTone.NEUTRAL) VoltColors.textSecondary else pillColor(tone),
        textAlign = TextAlign.Center,
    )
}

/** One round car-control button (mockups `.ctrl`); "on" marks the car's current state. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Mono White can't tint "on" apart from "off", so its on-state is filled with on-accent content.
    val filledOn = on && VoltColors.monoAccent
    val ring = if (on) VoltColors.accent.copy(alpha = ON_BORDER_ALPHA) else VoltColors.hairline
    val fill =
        when {
            filledOn -> VoltColors.accent
            on -> VoltColors.accent.copy(alpha = ON_FILL_ALPHA)
            else -> VoltColors.surface
        }
    Column(
        modifier =
            Modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(58.dp)
                .background(fill, CircleShape)
                .border(1.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    when {
                        filledOn -> VoltColors.onAccent
                        on -> VoltColors.accent
                        else -> VoltColors.textPrimary
                    },
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = VoltType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = if (on) VoltColors.textPrimary else VoltColors.textSecondary,
        )
    }
}

private fun CarControl.icon(): ImageVector =
    when (this) {
        CarControl.LOCK -> VoltIcons.Lock
        CarControl.UNLOCK -> VoltIcons.Unlock
        CarControl.START -> VoltIcons.Power
        CarControl.HORN -> VoltIcons.Horn
        CarControl.LIGHTS -> VoltIcons.Lights
    }

@Composable
private fun TileRow(
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        left(Modifier.weight(1f).fillMaxHeight())
        right(Modifier.weight(1f).fillMaxHeight())
    }
}

/** A Car tile (mockups `.card.ct`): caption and icon, the figure, and its lines. */
@Composable
private fun Tile(
    caption: String,
    icon: ImageVector,
    tile: CarTile,
    modifier: Modifier,
    iconTone: PillTone = PillTone.NEUTRAL,
) {
    Column(modifier.voltCard().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VoltLabel(caption, Modifier.weight(1f))
            IconSquare(icon, tone = iconTone, size = 28.dp, iconSize = 15.dp)
        }
        NumberUnit(
            value = tile.value,
            unit = tile.unit,
            size = 24f,
            color = if (tile.warnValue) VoltColors.warn else VoltColors.textPrimary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        tile.meter?.let { Meter(it, pillColor(tile.tone), Modifier.padding(bottom = 6.dp), height = 4f) }
        TileLines(tile.lines)
    }
}

@Composable
private fun ColumnScope.TileLines(lines: List<String>) {
    lines.forEach { line ->
        Text(line, style = VoltType.caption, color = VoltColors.textSecondary)
    }
    Spacer(Modifier.weight(1f, fill = false))
}

/** "No trouble codes · scanned 2 h ago" / "2 trouble codes · …" / "Not scanned yet" for the Health row. */
fun healthSummary(diag: DiagUiState): String = diag.summary()

/** Health row tint: muted before any scan, green when clean, red for any alert-level code, amber otherwise. */
fun healthTone(diag: DiagUiState): PillTone {
    val codes = diag.codes ?: return PillTone.NEUTRAL
    return when {
        codes.isEmpty() -> PillTone.EV
        codes.any { it.severity == DtcSeverity.ALERT } -> PillTone.BAD
        else -> PillTone.WARN
    }
}

/** The Car tab's nav badge: shown only while trouble codes are stored. */
fun carBadge(diag: DiagUiState): NavBadge? =
    when (healthTone(diag)) {
        PillTone.BAD -> NavBadge.BAD
        PillTone.WARN -> NavBadge.WARN
        else -> null
    }

/** "91% health · cells balanced (19 mV)", with whichever halves the car has reported. */
fun batterySummary(
    sohPct: Double?,
    spreadMv: Double?,
): String =
    listOfNotNull(
        sohPct?.let { "${it.roundToInt()}% health" },
        spreadMv?.let { cellBalanceText(it).replaceFirstChar(Char::lowercaseChar) },
    ).joinToString(" · ").ifEmpty { "Health appears after a battery read" }

@Composable
@Preview(widthDp = 412, heightDp = 1100)
private fun CarScreenPreview() {
    VoltTheme {
        CarScreen(
            DriveUiState.demoParked.copy(locked = true),
            CarUiState.demo,
            DiagUiState.demo,
            sohPct = 91.0,
            demo = true,
        )
    }
}

private const val ON_FILL_ALPHA = 0.14f
private const val ON_BORDER_ALPHA = 0.35f
private const val DISABLED_ALPHA = 0.4f
