package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import com.volttracker.obdpoc.data.DatabaseCorruption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "saved history was reset" notice for a screen while it is in front: [atMs] holds when damaged
 * history was set aside, or null. [start] reads it and keeps it current, so a damaged database found
 * while the screen is showing announces itself at once; [dismiss] acknowledges it for good.
 */
internal class HistoryResetNotice(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = DatabaseCorruption.prefs(appContext)
    private val pending = MutableStateFlow<Long?>(null)
    val atMs: StateFlow<Long?> = pending

    // Held in a field: SharedPreferences keeps only weak references to its listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun dismiss() {
        DatabaseCorruption.dismissNotice(appContext)
        refresh()
    }

    private fun refresh() {
        pending.value = DatabaseCorruption.pendingNoticeAtMs(appContext)
    }
}
