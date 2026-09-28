package com.volttracker.obdpoc.service

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide "is a VoltTracker screen on top" signal, delivered in-process to [ObdService].
 *
 * The activities used to report visibility with `startService(ACTION_APP_FOREGROUND/BACKGROUND)`
 * from onResume/onPause. Every start command makes ActivityThread run `QueuedWork.waitToFinish()`
 * on the main thread before `onStartCommand`, which blocks until every pending SharedPreferences
 * `apply()` has been fsynced. Whenever another app's UI covered ours (share sheet, pickers) the
 * pause-time start command therefore froze the main thread behind the prefs disk write — an ANR on
 * slow storage. It also created (and immediately stopped) the service on every resume/pause when
 * no session was running, and could be refused outright by the API 26+ background-start rules.
 *
 * The service lives in the app's own process, so a direct call is enough: the activities call
 * [report] and the running service (if any) is notified synchronously on the same main thread the
 * old start command ran on. A service created later reads [isForeground] as its starting value.
 */
object AppVisibility {
    /** Receives visibility changes on the main thread. */
    fun interface Listener {
        fun onAppVisibilityChanged(foreground: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    // Written on the main thread (activity lifecycle), read from the service's worker threads.
    @Volatile
    var isForeground: Boolean = true
        private set

    /** Records that a VoltTracker screen resumed ([foreground] true) or paused (false). */
    @MainThread
    fun report(foreground: Boolean) {
        isForeground = foreground
        for (listener in listeners) {
            listener.onAppVisibilityChanged(foreground)
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @VisibleForTesting
    fun resetForTest() {
        listeners.clear()
        isForeground = true
    }
}
