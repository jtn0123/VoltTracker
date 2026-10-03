package com.volttracker.obdpoc

import android.app.Activity
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls [check] on the activity's UI thread until it sets the flag or [timeoutMs] passes.
 *
 * Not `scenario.onActivity` in a loop: onActivity waits for the main looper to go IDLE before it
 * runs, and on a software-rendered CI emulator the dashboard draws one frame every ~2 s, so the
 * looper is rarely idle. Run 36256495217 failed that way — the demo service logged its first
 * sample 1 s after start, but the polling only got a turn after the 30 s deadline. This takes the
 * activity once, then posts each check with runOnUiThread, which only waits its turn in the queue.
 * [check] may set the flag later (e.g. from an evaluateJavascript callback).
 */
internal fun <A : Activity> ActivityScenario<A>.awaitOnUiThread(
    timeoutMs: Long,
    check: (activity: A, done: AtomicBoolean) -> Unit,
): Boolean {
    var launched: A? = null
    onActivity { launched = it }
    val activity = checkNotNull(launched) { "ActivityScenario produced no activity" }
    val done = AtomicBoolean(false)
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!done.get() && SystemClock.uptimeMillis() < deadline) {
        activity.runOnUiThread { if (!done.get()) check(activity, done) }
        Thread.sleep(UI_POLL_INTERVAL_MS)
    }
    return done.get()
}

private const val UI_POLL_INTERVAL_MS = 250L
