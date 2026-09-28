package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * App-level navigation the screens can trigger without knowing the shell: the gear opens
 * Settings, Car opens Health. Supplied by `VoltApp`; no-ops in isolated screen previews.
 */
class VoltNavActions(
    val openSettings: () -> Unit = {},
    val openHealth: () -> Unit = {},
)

val LocalVoltNav = staticCompositionLocalOf { VoltNavActions() }

/** Subtitle status dot: [live] adds the soft halo the mockups use for a streaming link. */
class AppBarDot(
    val color: Color,
    val live: Boolean = false,
)

/**
 * Screen header (mockups `.appbar`): optional back button, title + subtitle, and the Settings
 * gear. [showGear] is off on the Settings pages themselves.
 */
@Composable
fun VoltAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dot: AppBarDot? = null,
    onBack: (() -> Unit)? = null,
    showGear: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalVoltNav.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            IconCircleButton(VoltIcons.ChevronLeft, "Back", onBack, tint = VoltColors.textPrimary, iconSize = 18.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = VoltType.screenTitle, color = VoltColors.textPrimary)
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) {
                        StatusDot(dot)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(text = subtitle, style = VoltType.caption, color = VoltColors.textSecondary, maxLines = 1)
                }
            }
        }
        actions()
        if (showGear) {
            IconCircleButton(VoltIcons.Settings, "Settings", nav.openSettings)
        }
    }
}

@Composable
private fun StatusDot(dot: AppBarDot) {
    Box(
        modifier =
            Modifier
                .size(13.dp)
                .background(if (dot.live) dot.color.copy(alpha = HALO_ALPHA) else Color.Transparent, CircleShape)
                .padding(3.dp)
                .background(dot.color, CircleShape),
    )
}

/** 40dp round icon button on a card-colored disc (mockups `.iconbtn`). */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = VoltColors.textSecondary,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(VoltColors.surface)
                .border(1.dp, VoltColors.hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * The standard scrolling page: [VoltAppBar] on top, then [content] with the mockups' 16dp side
 * margin. Pass `contentPadding = 0.dp` for full-bleed rows (the content then pads itself).
 */
@Composable
fun VoltScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dot: AppBarDot? = null,
    onBack: (() -> Unit)? = null,
    showGear: Boolean = true,
    contentPadding: Dp = SCREEN_MARGIN,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
    ) {
        VoltAppBar(
            title = title,
            subtitle = subtitle,
            dot = dot,
            onBack = onBack,
            showGear = showGear,
            actions = actions,
            modifier = Modifier.padding(horizontal = SCREEN_MARGIN),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = contentPadding), content = content)
    }
}

/** Page side margin (mockups `.content { padding: 0 16px }`). */
val SCREEN_MARGIN = 16.dp

/** Status-dot convention for a connection label: live Volt-green when connected, faint otherwise. */
@Composable
fun connectionDot(connected: Boolean): AppBarDot =
    if (connected) AppBarDot(VoltColors.energy, live = true) else AppBarDot(VoltColors.textTertiary)

private const val HALO_ALPHA = 0.18f
