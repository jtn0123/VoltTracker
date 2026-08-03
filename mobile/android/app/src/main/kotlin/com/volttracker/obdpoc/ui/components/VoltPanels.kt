package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/** Soft rounded panel — the only "card" chrome in the design language. */
@Composable
fun VoltPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(VoltColors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        content = content,
    )
}

/** Small upper-case section label, e.g. "HV BATTERY". */
@Composable
fun VoltLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = VoltColors.textSecondary,
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

/** Status pill: colored dot + short text, e.g. "LIVE · 1 HZ". */
@Composable
fun VoltStatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(VoltColors.surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Spacer(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = text.uppercase(Locale.US),
            style = VoltType.label,
            color = VoltColors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Pill button. Accent variant for the primary action of a screen. */
@Composable
fun VoltButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(if (accent) VoltColors.energy else VoltColors.surfaceElevated)
                .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
                .padding(horizontal = 17.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = VoltType.body,
            color = if (accent) VoltColors.onAccent else VoltColors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}
