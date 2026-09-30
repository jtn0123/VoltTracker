package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.volttracker.obdpoc.ui.theme.VoltColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pull down to re-read: wraps a scrolling page so dragging past its top calls [onRefresh]. The
 * reads behind it take a few milliseconds and report back through the screen's own state, so the
 * spinner simply stays up long enough to be seen ([REFRESH_SHOWN_MS]) rather than flashing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoltRefreshBox(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                onRefresh()
                delay(REFRESH_SHOWN_MS)
                refreshing = false
            }
        },
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = VoltColors.surfaceElevated,
                color = VoltColors.accent,
            )
        },
    ) {
        Box { content() }
    }
}

private const val REFRESH_SHOWN_MS = 700L
