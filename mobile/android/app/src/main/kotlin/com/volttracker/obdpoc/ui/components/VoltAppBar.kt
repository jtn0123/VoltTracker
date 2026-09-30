package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltSpacing
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
    statusSubtitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalVoltNav.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            IconCircleButton(VoltIcons.ChevronLeft, "Back", onBack, tint = VoltColors.textPrimary, iconSize = 18.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = VoltType.screenTitle,
                color = VoltColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val demo = LocalVoltPrefs.current.demo
            // Demo data must never pass for a live car: no live dot, and no adapter in the subtitle.
            val shownDot = dot.takeUnless { demo }
            val shownSubtitle = appBarSubtitle(subtitle, statusSubtitle, demo)
            if (shownSubtitle != null || demo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shownDot != null) {
                        StatusDot(shownDot)
                        Spacer(Modifier.size(6.dp))
                    }
                    // Demo data must never pass for the car's: every header flags it.
                    if (demo) {
                        VoltPill("Demo", PillTone.DEMO, dot = false, small = true)
                        Spacer(Modifier.size(6.dp))
                    }
                    if (shownSubtitle != null) {
                        Text(
                            text = shownSubtitle,
                            style = VoltType.caption,
                            color = VoltColors.textSecondary,
                            // Wraps to a second line rather than cutting "Sample data · 45 drives ·
                            // 1038 mi · September" mid-word beside the Demo pill.
                            maxLines = SUBTITLE_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
    // Connecting → live fades the dot and its halo in rather than flipping them.
    val color = glideColor(dot.color, "status-dot")
    val halo = glideColor(if (dot.live) dot.color.copy(alpha = HALO_ALPHA) else Color.Transparent, "status-halo")
    Box(
        modifier =
            Modifier
                .size(13.dp)
                .background(halo, CircleShape)
                .padding(3.dp)
                .background(color, CircleShape),
    )
}

/** 40dp round icon button on a card-colored disc (mockups `.iconbtn`), in a 48dp touch target. */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = VoltColors.textSecondary,
    iconSize: Dp = 20.dp,
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .pressScale(interactions, pressed = ICON_PRESSED_SCALE)
                .clip(CircleShape)
                .background(VoltColors.surface)
                .border(1.dp, VoltColors.hairline, CircleShape)
                .clickable(
                    interactionSource = interactions,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                ),
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
 * margin. Pass `contentPadding = 0.dp` for full-bleed rows (the content then pads itself), and a
 * [scrollState] to move the page from outside.
 */
@Composable
fun VoltScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dot: AppBarDot? = null,
    onBack: (() -> Unit)? = null,
    showGear: Boolean = true,
    statusSubtitle: Boolean = false,
    contentPadding: Dp = SCREEN_MARGIN,
    scrollState: ScrollState = rememberScrollState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 20.dp),
    ) {
        VoltAppBar(
            title = title,
            subtitle = subtitle,
            dot = dot,
            onBack = onBack,
            showGear = showGear,
            statusSubtitle = statusSubtitle,
            actions = actions,
            modifier = Modifier.padding(horizontal = SCREEN_MARGIN),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = contentPadding), content = content)
    }
}

/**
 * The app-bar subtitle as shown. During the demo a connection status ([statusSubtitle]: Drive,
 * Charge) becomes "Sample data", and any other subtitle gets it as a prefix, so no header names an
 * adapter or claims a live link while sample data is on screen.
 */
fun appBarSubtitle(
    subtitle: String?,
    statusSubtitle: Boolean,
    demo: Boolean,
): String? =
    when {
        !demo || subtitle == null -> subtitle
        statusSubtitle -> DEMO_SUBTITLE
        else -> "$DEMO_SUBTITLE · $subtitle"
    }

private const val SUBTITLE_MAX_LINES = 2

/** What every header says while the Demo / Testing stream is on screen. */
const val DEMO_SUBTITLE = "Sample data"

/** Page side margin (mockups `.content { padding: 0 16px }`). */
val SCREEN_MARGIN = VoltSpacing.screen

/** Status-dot convention for a connection label: live Volt-green when connected, faint otherwise. */
@Composable
fun connectionDot(connected: Boolean): AppBarDot =
    if (connected) AppBarDot(VoltColors.energy, live = true) else AppBarDot(VoltColors.textTertiary)

private const val HALO_ALPHA = 0.18f

/** A small round button shrinks a little more than a wide one, so the press still reads. */
private const val ICON_PRESSED_SCALE = 0.9f
