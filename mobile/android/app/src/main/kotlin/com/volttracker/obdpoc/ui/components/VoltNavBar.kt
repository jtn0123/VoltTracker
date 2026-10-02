package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The same tabs as a left-hand rail, for landscape phones: a bottom bar there costs a sixth of an
 * already short screen, while the rail spends width the cards don't need.
 */
@Composable
fun VoltNavRail(
    selected: VoltTab,
    modifier: Modifier = Modifier,
    badges: Map<VoltTab, NavBadge> = emptyMap(),
    onSelect: (VoltTab) -> Unit = {},
) {
    Row(modifier = modifier.fillMaxHeight().background(VoltColors.bg)) {
        Column(
            modifier =
                Modifier
                    .width(RAIL_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            verticalArrangement = Arrangement.Center,
        ) {
            VoltTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    active = tab == selected,
                    badge = badges[tab],
                    onClick = { onSelect(tab) },
                    modifier = Modifier.fillMaxWidth().height(NAV_HEIGHT),
                )
            }
        }
        VerticalDivider(color = VoltColors.hairline, thickness = 1.dp)
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
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .selectable(
                    selected = active,
                    interactionSource = interactions,
                    // The pill is the feedback; a ripple across the whole cell would fight it.
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ).semantics { contentDescription = tab.label + badgeText },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Mono White: a white tint behind a white icon barely reads, so the pill is filled instead.
        val mono = VoltColors.monoAccent
        val pill = if (mono) VoltColors.accent else VoltColors.accent.copy(alpha = INDICATOR_ALPHA)
        // The pill grows out from the icon and fades in as its tab is chosen.
        val shown = glideState(if (active) 1f else 0f, "nav-pill")
        Box(
            modifier =
                Modifier
                    .padding(top = 9.dp)
                    .size(width = 56.dp, height = 30.dp)
                    .pressScale(interactions)
                    .graphicsLayer {
                        alpha = shown.value
                        scaleX = PILL_MIN_SCALE + (1f - PILL_MIN_SCALE) * shown.value
                    }.background(pill, VoltShapes.chip),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 12.5.dp).pressScale(interactions),
        ) {
            Icon(
                imageVector = tab.icon(),
                contentDescription = null,
                tint =
                    glideColor(
                        when {
                            !active -> VoltColors.textTertiary
                            mono -> VoltColors.onAccent
                            else -> VoltColors.accent
                        },
                        "nav-icon",
                    ),
                modifier = Modifier.size(23.dp),
            )
            Spacer(Modifier.height(4.dp))
            // The bar is a fixed 67dp, so the label's text scale is capped (it fits at 1.3×) and a
            // long label ellipsizes rather than pushing out of the bar.
            CappedTextScale {
                Text(
                    text = tab.label,
                    fontFamily = VoltFonts.hanken,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    color = glideColor(if (active) VoltColors.textPrimary else VoltColors.textTertiary, "nav-label"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
private val RAIL_WIDTH = 80.dp
private const val INDICATOR_ALPHA = 0.14f

/** How narrow the pill starts before it grows to full width. */
private const val PILL_MIN_SCALE = 0.55f
