package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.OledAccent
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.contrastRatio

/**
 * Settings → Appearance → Accent: one color swatch per [OledAccent]. The chosen one gets a ring
 * in the text color and a center dot in its own on-accent color; each swatch is a 48dp radio
 * target announced by name ("Cyan accent").
 */
@Composable
internal fun AccentSwatches(
    selected: OledAccent,
    onSelect: (OledAccent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OledAccent.entries.forEach { accent ->
            AccentSwatch(accent = accent, chosen = accent == selected) { onSelect(accent) }
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: OledAccent,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (chosen) VoltColors.textPrimary else Color.Transparent
    // A swatch close to the card color (Mono White on a light card) gets a visible outline.
    val surface = VoltColors.surface
    val outline =
        if (contrastRatio(accent.volt, surface) < SWATCH_MIN_CONTRAST) VoltColors.textTertiary else VoltColors.line2
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .selectable(selected = chosen, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = "${accent.label} accent" }
                .padding(3.dp)
                .border(2.dp, ring, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(accent.volt)
                .border(1.dp, outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Box(Modifier.size(10.dp).clip(CircleShape).background(accent.onVolt))
    }
}

private const val SWATCH_MIN_CONTRAST = 1.5
