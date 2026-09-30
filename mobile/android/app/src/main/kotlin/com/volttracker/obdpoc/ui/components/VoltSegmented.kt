package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * Segmented control (mockups `.seg`): a sunken 36dp track with one raised selected segment. Each
 * segment's touch target spans the full 48dp row height, while the track is drawn inset so the
 * control still looks like the mockup. [options] are the visible labels; [selectedIndex] is the
 * chosen one.
 *
 * [role] is how TalkBack announces each segment: [Role.Tab] where the control switches a view
 * (Drive's Focus / Detailed, Insights periods), [Role.RadioButton] for a stored choice (Settings).
 * When not [enabled] (the setting it tunes is off) it dims and ignores taps.
 */
@Composable
fun VoltSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit = {},
) {
    val track = VoltColors.surfaceElevated
    Row(
        modifier =
            modifier
                .drawBehind {
                    val inset = TRACK_INSET.toPx()
                    drawRoundRect(
                        color = track,
                        topLeft = Offset(0f, inset),
                        size = Size(size.width, size.height - 2 * inset),
                        cornerRadius = CornerRadius(TRACK_RADIUS.toPx()),
                    )
                }.padding(horizontal = 3.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            Segment(
                label = label,
                selected = index == selectedIndex,
                role = role,
                enabled = enabled,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    role: Role,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selected: the raised segment, outlined so it reads on the dark themes too (where the
    // mockups' surface-3 fill sits only a shade above the track).
    val fill = if (VoltColors.isDark) VoltColors.surface3 else VoltColors.surface
    val shape = VoltShapes.inner
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .selectable(selected = selected, enabled = enabled, onClick = onClick, role = role)
                .padding(vertical = SEGMENT_INSET),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp)
                    .clip(shape)
                    // The raised segment fades from the old choice to the new one.
                    .background(glideColor(if (selected) fill else fill.copy(alpha = 0f), "segment-fill"))
                    .border(
                        1.dp,
                        glideColor(
                            if (selected) VoltColors.line2 else VoltColors.line2.copy(alpha = 0f),
                            "segment-line",
                        ),
                        shape,
                    ).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style =
                    VoltType.body.copy(
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                color = glideColor(if (selected) VoltColors.textPrimary else VoltColors.textSecondary, "segment-text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Space above/below the drawn track inside the 48dp row. */
private val TRACK_INSET = 6.dp

/** Track inset plus its 3dp padding: where the raised segment sits. */
private val SEGMENT_INSET = 9.dp
private val TRACK_RADIUS = 12.dp
