package com.volttracker.obdpoc.ui.components

/** An optional way out of a [VoltEmptyState], e.g. "Open Settings". */
class EmptyAction(
    val label: String,
    val onClick: () -> Unit,
)
