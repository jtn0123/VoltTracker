package com.volttracker.obdpoc.ui.components

/** An optional way out of a [VoltEmptyState], e.g. "Open Settings". */
class EmptyAction(
    val label: String,
    val onClick: () -> Unit,
)

/** The two ways out of "nothing here because no car is connected": connect, or look around the demo. */
fun VoltNavActions.connectAction(): EmptyAction = EmptyAction("Connect", connect)

fun VoltNavActions.demoAction(): EmptyAction = EmptyAction("Try the demo", startDemo)

/** The way out of a failed read. */
fun VoltNavActions.retryAction(): EmptyAction = EmptyAction("Try again", refresh)
