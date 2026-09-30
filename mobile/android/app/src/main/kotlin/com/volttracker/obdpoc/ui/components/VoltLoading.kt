package com.volttracker.obdpoc.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import kotlin.math.abs

/**
 * Swaps [content] with a short cross-fade when [targetState] changes (loading → loaded, one status
 * → the next) and eases the height between the two, so nothing on the page pops or jumps.
 */
@Composable
fun <T> VoltFade(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "fade",
    content: @Composable (T) -> Unit,
) {
    val reduceMotion = LocalVoltPrefs.current.reduceMotion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            fadeIn(VoltMotion.spec(VoltMotion.STANDARD_MS, reduceMotion)) togetherWith
                fadeOut(VoltMotion.spec(VoltMotion.FAST_MS, reduceMotion))
        },
        label = label,
    ) { content(it) }
}

/** Eases a card's height when its content grows or shrinks (a scan finding codes, a row appearing). */
fun Modifier.voltAnimateSize(): Modifier =
    composed { animateContentSize(VoltMotion.spec(VoltMotion.STANDARD_MS, LocalVoltPrefs.current.reduceMotion)) }

/**
 * What a list shows while its history is being read: softly pulsing placeholder rows in the shape
 * of what is coming, instead of a line of text that jumps to a full page. TalkBack reads [title].
 */
@Composable
fun VoltLoading(
    title: String,
    modifier: Modifier = Modifier,
    rows: Int = SKELETON_ROWS,
) {
    val phase = rememberLoopPhase(active = true, period = 1f, durationMs = PULSE_MS, label = "loading-pulse")
    // A triangle wave: dim → bright → dim. Steady (bright) under reduce-motion.
    val pulse = phase?.let { 1f - abs(2f * it - 1f) } ?: 1f
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = title }
                .graphicsLayer { alpha = PULSE_MIN + (1f - PULSE_MIN) * pulse },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).background(VoltColors.surfaceElevated, VoltShapes.inner))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    // Rows differ in length so the block reads as a list, not a table.
                    Bone(TITLE_WIDTHS[row % TITLE_WIDTHS.size])
                    Bone(SUB_WIDTHS[row % SUB_WIDTHS.size])
                }
            }
        }
    }
}

@Composable
private fun Bone(width: Float) {
    Box(Modifier.fillMaxWidth(width).height(10.dp).background(VoltColors.surfaceElevated, VoltShapes.chip))
}

private const val SKELETON_ROWS = 3
private const val PULSE_MS = 1_400
private const val PULSE_MIN = 0.45f
private val TITLE_WIDTHS = listOf(0.62f, 0.48f, 0.56f)
private val SUB_WIDTHS = listOf(0.34f, 0.4f, 0.28f)
