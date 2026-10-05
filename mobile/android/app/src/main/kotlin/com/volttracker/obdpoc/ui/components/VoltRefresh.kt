package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.volttracker.obdpoc.ui.theme.VoltColors

/**
 * Pull down to re-read: wraps a scrolling page so dragging past its top calls [onRefresh]. The
 * host keeps [refreshing] true until the history reads and their follow-ups complete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoltRefreshBox(
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                onRefresh()
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
