package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltShapes

/**
 * Full-width bottom navigation (mockups `.nav`): a hairline-topped bar on the page color, one
 * item per [VoltTab], the active item marked by a Volt-tinted pill behind its icon.
 */
@Composable
fun VoltNavBar(
    selected: VoltTab,
    modifier: Modifier = Modifier,
    badges: Map<VoltTab, NavBadge> = emptyMap(),
    onSelect: (VoltTab) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().background(VoltColors.bg)) {
        HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(NAV_HEIGHT)
                    .padding(horizontal = 6.dp)
                    .selectableGroup(),
        ) {
            VoltTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    active = tab == selected,
                    badge = badges[tab],
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: VoltTab,
    active: Boolean,
    badge: NavBadge?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badgeText = badge?.let { if (it == NavBadge.BAD) ", has faults" else ", has warnings" }.orEmpty()
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .selectable(selected = active, onClick = onClick, role = Role.Tab)
                .semantics { contentDescription = tab.label + badgeText },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Mono White: a white tint behind a white icon barely reads, so the pill is filled instead.
        val mono = VoltColors.monoAccent
        Box(
            modifier =
                Modifier
                    .padding(top = 9.dp)
                    .size(width = 56.dp, height = 30.dp)
                    .background(
                        when {
                            !active -> Color.Transparent
                            mono -> VoltColors.accent
                            else -> VoltColors.accent.copy(alpha = INDICATOR_ALPHA)
                        },
                        VoltShapes.chip,
                    ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.5.dp)) {
            Icon(
                imageVector = tab.icon(),
                contentDescription = null,
                tint =
                    when {
                        !active -> VoltColors.textTertiary
                        mono -> VoltColors.onAccent
                        else -> VoltColors.accent
                    },
                modifier = Modifier.size(23.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tab.label,
                fontFamily = VoltFonts.hanken,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                color = if (active) VoltColors.textPrimary else VoltColors.textTertiary,
            )
        }
        if (badge != null) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .offset(x = 11.dp)
                        .size(8.dp)
                        .background(if (badge == NavBadge.BAD) VoltColors.alert else VoltColors.warn, CircleShape)
                        .border(2.dp, VoltColors.bg, CircleShape),
            )
        }
    }
}

private val NAV_HEIGHT = 67.dp
private const val INDICATOR_ALPHA = 0.14f
