package com.volttracker.obdpoc.ui.car

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.service.ObdService

/**
 * Keeps the live session listening to the body bus nonstop while the Car tab is on screen, so a
 * window, door or lock change is heard as it happens rather than in the next 45 s window.
 *
 * The service only listens for a lease ([LEASE_MS]); this renews it every [RENEW_MS] while the
 * tab shows and the screen is in front, and ends it (a 0 lease) when either stops. A lost "stop"
 * costs nothing: the lease just runs out. Nothing is sent without a live session, so opening the
 * tab never starts the service.
 */
class BodyFocusPinger(
    private val send: (Long) -> Unit,
    private val hasSession: () -> Boolean,
    private val scheduler: Scheduler,
) {
    /** Runs a task later on the main thread; a seam so tests can step time. */
    interface Scheduler {
        fun schedule(
            delayMs: Long,
            task: Runnable,
        )

        fun cancel(task: Runnable)
    }

    private var tabShown = false
    private var resumed = false
    private var leased = false
    private val renew = Runnable { tick() }

    fun setCarTabShown(shown: Boolean) {
        tabShown = shown
        update()
    }

    fun setResumed(isResumed: Boolean) {
        resumed = isResumed
        update()
    }

    private fun update() {
        scheduler.cancel(renew)
        if (tabShown && resumed) {
            tick()
        } else if (leased) {
            leased = false
            if (hasSession()) send(0L)
        }
    }

    private fun tick() {
        if (hasSession()) {
            send(LEASE_MS)
            leased = true
        }
        scheduler.schedule(RENEW_MS, renew)
    }

    companion object {
        /** Long enough to outlast a missed renewal or two; the service caps it at 60 s. */
        const val LEASE_MS = 45_000L
        const val RENEW_MS = 20_000L

        /** The real pinger: renews on the main thread and asks [ObdService] for the lease. */
        fun forService(
            context: Context,
            hasSession: () -> Boolean = ObdService::hasActiveSession,
        ): BodyFocusPinger {
            val handler = Handler(Looper.getMainLooper())
            return BodyFocusPinger(
                send = { ms ->
                    try {
                        context.startService(
                            Intent(context, ObdService::class.java)
                                .setAction(ObdService.ACTION_BODY_FOCUS)
                                .putExtra(ObdService.EXTRA_DURATION_MS, ms),
                        )
                    } catch (ex: RuntimeException) {
                        Log.w(AppPrefs.LOG_TAG, "body focus dispatch failed", ex)
                    }
                },
                hasSession = hasSession,
                scheduler =
                    object : Scheduler {
                        override fun schedule(
                            delayMs: Long,
                            task: Runnable,
                        ) {
                            handler.postDelayed(task, delayMs)
                        }

                        override fun cancel(task: Runnable) = handler.removeCallbacks(task)
                    },
            )
        }
    }
}
