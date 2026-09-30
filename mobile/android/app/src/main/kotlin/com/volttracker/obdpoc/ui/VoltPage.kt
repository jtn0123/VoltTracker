package com.volttracker.obdpoc.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.PageMotion
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.components.pageTransition
import com.volttracker.obdpoc.ui.theme.VoltColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What fills the page area: a tab, or the [route] pushed [depth] levels on top of it. */
internal data class VoltPage(
    val tab: VoltTab,
    val route: VoltRoute?,
    val depth: Int,
)

/** Deeper is a push, shallower a pop; a different tab at the top level is a plain tab switch. */
internal fun pageMotion(
    from: VoltPage,
    to: VoltPage,
): PageMotion =
    when {
        to.depth > from.depth -> PageMotion.PUSH
        to.depth < from.depth -> PageMotion.POP
        to.depth > 0 -> PageMotion.PUSH
        else -> PageMotion.TAB
    }

/**
 * The page area, animated: tabs fade through, a pushed screen slides in over the one below and
 * slides back out on the way back. The system back gesture drags the top screen with the finger
 * ([below] peeks out underneath) and either finishes the pop or springs back if it is let go.
 */
@Composable
internal fun VoltPages(
    target: VoltPage,
    below: VoltPage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (VoltPage) -> Unit,
) {
    val reduceMotion = LocalVoltPrefs.current.reduceMotion
    val seek = remember { SeekableTransitionState(target) }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(target)
    LaunchedEffect(target, reduceMotion) {
        if (reduceMotion) seek.snapTo(target) else seek.animateTo(target)
    }
    PredictiveBackHandler(enabled = target.depth > 0) { progress ->
        try {
            progress.collect { if (!reduceMotion) seek.seekTo(it.progress * BACK_PEEK, below) }
            onBack()
        } catch (e: CancellationException) {
            // Let go before the threshold: the top screen settles back where it was.
            scope.launch { seek.animateTo(current) }
            throw e
        }
    }
    rememberTransition(seek, label = "page").AnimatedContent(
        modifier = modifier,
        transitionSpec = { pageTransition(pageMotion(initialState, targetState), targetState.depth, reduceMotion) },
        contentKey = { it },
    ) { shown ->
        // Opaque, so a screen sliding over another never shows the one below through it.
        Box(Modifier.fillMaxSize().background(VoltColors.bg)) { content(shown) }
    }
}

/**
 * How much of the pop a full back swipe previews: about half the slide, so the screen follows the
 * finger without looking finished before it is let go.
 */
private const val BACK_PEEK = 0.18f
