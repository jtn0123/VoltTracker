package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * Segmented control (mockups `.seg`): a sunken track with one raised selected segment.
 * [options] are the visible labels; [selectedIndex] is the chosen one.
 */
@Composable
fun VoltSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    val onColor = if (VoltColors.isDark) VoltColors.surface3 else VoltColors.surface
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(VoltColors.surfaceElevated)
                .padding(3.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) onColor else Color.Transparent)
                        .selectable(selected = selected, onClick = { onSelect(index) }, role = Role.Tab)
                        .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = label,
                    style = VoltType.body.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = if (selected) VoltColors.textPrimary else VoltColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
