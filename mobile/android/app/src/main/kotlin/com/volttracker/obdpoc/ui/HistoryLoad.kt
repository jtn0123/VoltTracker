package com.volttracker.obdpoc.ui

/**
 * Where a tab's saved history stands. A tab shows a placeholder while [LOADING] rather than its
 * empty state, so "No drives logged yet" never flashes before the first read lands. [FAILED] is
 * only reached when no read has ever succeeded: a later failure keeps the list already shown.
 */
enum class HistoryLoad {
    LOADING,
    LOADED,
    FAILED,
    ;

    /** The state after a read fails: an already-loaded list stays loaded. */
    fun failed(): HistoryLoad = if (this == LOADED) LOADED else FAILED
}
