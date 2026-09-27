package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/** Card chrome (mockups `.card`): 20dp corners, hairline border, a soft shadow in light. */
@Composable
fun Modifier.voltCard(radius: Dp = 20.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val lifted =
        if (VoltColors.isDark) {
            this
        } else {
            this.shadow(elevation = 6.dp, shape = shape, ambientColor = CARD_SHADOW, spotColor = CARD_SHADOW)
        }
    return lifted
        .clip(shape)
        .background(VoltColors.surface)
        .border(1.dp, VoltColors.hairline, shape)
}

private val CARD_SHADOW = Color(0x33102030)

/** The one "card" container in the design language. */
@Composable
fun VoltPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .voltCard()
                .padding(padding),
        content = content,
    )
}

/** Small upper-case section label, e.g. "HV BATTERY". */
@Composable
fun VoltLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = VoltColors.textTertiary,
) {
    Text(
        text = text.uppercase(Locale.US),
        style = VoltType.label,
        color = color,
        modifier = modifier,
    )
}

/** Label over value — the basic stat block. */
@Composable
fun VoltStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = VoltColors.textPrimary,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        VoltLabel(label)
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = VoltType.value,
                color = valueColor,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            )
            if (unit != null) {
                Spacer(Modifier.size(4.dp))
                Text(text = unit, style = VoltType.caption, color = VoltColors.textSecondary)
            }
        }
    }
}

/** Status pill: colored dot + short text, e.g. "LIVE · 1 HZ" (mockups `.pill.neutral`). */
@Composable
fun VoltStatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    PillShell(background = VoltColors.surfaceElevated, modifier = modifier) {
        Dot(dotColor)
        PillText(text, VoltColors.textSecondary)
    }
}

/** Meaning of a [VoltPill]: its text color and tinted background. */
enum class PillTone { EV, GAS, VOLT, NEUTRAL, WARN, BAD }

/**
 * Tinted status pill (mockups `.pill.ev/.gas/.volt/.warn/.bad/.neutral`): upper-case caps,
 * optional leading dot or [icon].
 */
@Composable
fun VoltPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
    small: Boolean = false,
    icon: ImageVector? = null,
) {
    val color = pillColor(tone)
    val background =
        when (tone) {
            PillTone.NEUTRAL -> VoltColors.surfaceElevated
            PillTone.EV, PillTone.VOLT -> color.copy(alpha = 0.13f)
            PillTone.GAS, PillTone.BAD -> color.copy(alpha = 0.14f)
            PillTone.WARN -> color.copy(alpha = 0.15f)
        }
    PillShell(background = background, modifier = modifier, small = small) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            dot -> Dot(color)
        }
        PillText(text, color, small)
    }
}

/** Text color for a [PillTone]. */
@Composable
fun pillColor(tone: PillTone): Color =
    when (tone) {
        PillTone.EV -> VoltColors.energy
        PillTone.GAS -> VoltColors.gas
        PillTone.VOLT -> VoltColors.accent
        PillTone.NEUTRAL -> VoltColors.textSecondary
        PillTone.WARN -> VoltColors.warn
        PillTone.BAD -> VoltColors.alert
    }

@Composable
private fun PillShell(
    background: Color,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .height(if (small) 22.dp else 28.dp)
                .clip(RoundedCornerShape(50))
                .background(background)
                .padding(start = if (small) 8.dp else 10.dp, end = if (small) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) { content() }
}

@Composable
private fun Dot(color: Color) {
    Spacer(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun PillText(
    text: String,
    color: Color,
    small: Boolean = false,
) {
    Text(
        text = text.uppercase(Locale.US),
        style = if (small) VoltType.pill.copy(fontSize = 10.5.sp) else VoltType.pill,
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

/** Visual weight of a [VoltButton] (mockups `.btn`, `.btn.primary`, `.btn.ghost`). */
enum class ButtonStyle { PRIMARY, TONAL, GHOST }

/** 44dp button. Accent (primary) for the one main action of a screen. */
@Composable
fun VoltButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    style: ButtonStyle = if (accent) ButtonStyle.PRIMARY else ButtonStyle.TONAL,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (style == ButtonStyle.PRIMARY) VoltColors.onAccent else VoltColors.textPrimary
    val base =
        modifier
            .height(height)
            .clip(shape)
    val filled =
        when (style) {
            ButtonStyle.PRIMARY -> base.background(VoltColors.accent)
            ButtonStyle.TONAL -> base.background(VoltColors.surfaceElevated)
            ButtonStyle.GHOST -> base.border(1.dp, VoltColors.line2, shape)
        }
    Row(
        modifier =
            filled
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = VoltType.bodyStrong.copy(fontSize = 14.sp),
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}
