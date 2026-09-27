package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/** Tinted rounded-square icon badge (mockups `.sq`); [tone] NEUTRAL is the muted default. */
@Composable
fun IconSquare(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.NEUTRAL,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
) {
    val (bg, fg) = squareColors(tone)
    Box(
        modifier =
            modifier
                .size(size)
                .background(bg, RoundedCornerShape(size * SQUARE_RADIUS_RATIO)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun squareColors(tone: PillTone) =
    if (tone == PillTone.NEUTRAL) {
        VoltColors.surfaceElevated to VoltColors.textSecondary
    } else {
        val c = pillColor(tone)
        c.copy(alpha = SQUARE_TINT_ALPHA) to c
    }

/** A card of hairline-separated rows (mockups `.card.list`). Put [VoltListRow]s inside. */
@Composable
fun VoltListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    VoltPanel(modifier = modifier, padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), content = content)
}

/** Hairline between [VoltListRow]s. */
@Composable
fun VoltListDivider() {
    HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
}

/**
 * One navigable row: icon square, bold title (+ optional subtitle), optional trailing value, and
 * a chevron. [onClick] null renders a static row with no chevron.
 */
@Composable
fun VoltListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    tone: PillTone = PillTone.NEUTRAL,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(clickable)
                .padding(vertical = if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquare(
            icon = icon,
            tone = tone,
            size = if (compact) 30.dp else 34.dp,
            iconSize = if (compact) 16.dp else 18.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.size(1.dp))
                Text(
                    text = subtitle,
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(text = value, style = VoltType.caption.copy(fontSize = VALUE_SIZE), color = VoltColors.textSecondary)
        }
        if (onClick != null) {
            Icon(
                imageVector = VoltIcons.ChevronRight,
                contentDescription = null,
                tint = VoltColors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Upper-case group heading above a list card (mockups `.st-g`). */
@Composable
fun VoltGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    VoltLabel(text = text, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

private const val SQUARE_RADIUS_RATIO = 0.32f
private const val SQUARE_TINT_ALPHA = 0.13f
private val VALUE_SIZE = 13.sp
