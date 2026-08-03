package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/** Floating pill navigation bar, one item per dashboard tab. */
@Composable
fun VoltBottomNav(
    selected: VoltTab,
    modifier: Modifier = Modifier,
    onSelect: (VoltTab) -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .selectableGroup()
                .clip(RoundedCornerShape(28.dp))
                .background(VoltColors.surfaceElevated.copy(alpha = 0.96f))
                .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoltTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) VoltColors.surface else VoltColors.surfaceElevated.copy(alpha = 0f))
                        .selectable(
                            selected = active,
                            onClick = { onSelect(tab) },
                            role = Role.Tab,
                        ).padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) VoltColors.energy else VoltColors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(3.dp))
                Text(
                    text = tab.label,
                    style = VoltType.label.copy(fontSize = 10.sp, letterSpacing = 0.02.sp),
                    color = if (active) VoltColors.textPrimary else VoltColors.textTertiary,
                )
            }
        }
    }
}
