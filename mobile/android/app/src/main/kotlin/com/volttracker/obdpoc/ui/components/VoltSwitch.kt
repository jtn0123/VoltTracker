package com.volttracker.obdpoc.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes

/**
 * A compact switch (44×26dp): a track with a sliding thumb — filled accent with an on-accent thumb
 * when [on], a sunken outlined track with a muted thumb when off. Purely visual: the enclosing row
 * owns the toggle semantics (`Modifier.toggleable(role = Role.Switch)`), so TalkBack reads the row's
 * label with the switch state once.
 */
@Composable
fun VoltSwitch(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalVoltPrefs.current.reduceMotion
    val thumbOffset by animateDpAsState(
        if (on) THUMB_TRAVEL else 0.dp,
        VoltMotion.spec(VoltMotion.STANDARD_MS, reduceMotion),
        label = "switchThumb",
    )
    Box(
        modifier =
            modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(VoltShapes.chip)
                .background(glideColor(if (on) VoltColors.accent else VoltColors.surface3, "switch-track"))
                .border(
                    1.dp,
                    glideColor(if (on) VoltColors.accent else VoltColors.line2, "switch-border"),
                    VoltShapes.chip,
                ).padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(20.dp)
                .clip(CircleShape)
                .background(glideColor(if (on) VoltColors.onAccent else VoltColors.textSecondary, "switch-thumb")),
        )
    }
}

/** 44dp track − 2×3dp padding − 20dp thumb. */
private val THUMB_TRAVEL = 18.dp
