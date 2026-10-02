package com.volttracker.obdpoc

/**
 * Tracks whether a classic dashboard ([MainActivity]) instance is alive in this process. It holds
 * its own database handle, so the native dashboard hands a restore over to it instead of swapping
 * the database file underneath it.
 */
object ClassicDashboardPresence {
    private var live = 0

    @Synchronized
    fun onCreated() {
        live++
    }

    @Synchronized
    fun onDestroyed() {
        if (live > 0) live--
    }

    @Synchronized
    fun isAlive(): Boolean = live > 0
}
