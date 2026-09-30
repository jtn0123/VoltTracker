package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * The one empty / loading / failed state (in place of a list or chart): a short title, an
 * optional line saying what fills it, and up to two tappable ways to fix it, so no empty screen is
 * a dead end. [inCard] draws its own card; pass false inside a card that already has one.
 */
@Composable
fun VoltEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: EmptyAction? = null,
    inCard: Boolean = true,
    secondAction: EmptyAction? = null,
) {
    val content: @Composable () -> Unit = {
        Text(title, style = VoltType.bodyStrong, color = VoltColors.textPrimary)
        body?.let {
            Text(
                text = it,
                style = VoltType.body,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val links = listOfNotNull(action, secondAction)
        if (links.isNotEmpty()) {
            // Side by side, or one under the other when large text leaves no room for both.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { links.forEach { EmptyLink(it) } }
        }
    }
    if (inCard) {
        VoltPanel(modifier = modifier) { content() }
    } else {
        Column(modifier = modifier.padding(vertical = 10.dp)) { content() }
    }
}

/** An inline text link (mockups' accent-coloured "Open Settings ›"), in a 48dp touch target. */
@Composable
fun EmptyLink(
    action: EmptyAction,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "${action.label} ›",
        style = VoltType.body.copy(fontWeight = FontWeight.SemiBold),
        color = VoltColors.accent,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(VoltShapes.inner)
                .clickable(role = Role.Button, onClick = action.onClick)
                .padding(vertical = 4.dp),
    )
}

/** A small grey chip (mockups `.chip`): the charge limit, the cockpit's outside temperature. */
@Composable
fun VoltChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = VoltType.caption,
        color = VoltColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .heightIn(min = 26.dp)
                .clip(VoltShapes.chip)
                .background(VoltColors.surfaceElevated)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
