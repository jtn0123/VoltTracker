package com.volttracker.obdpoc

import android.content.Context
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Pays the first-touch disk cost of the app's private files off the main thread.
 *
 * The first `getSharedPreferences` / `filesDir` call in a fresh process stats the data directory and,
 * for the prefs, waits on the XML load. Done inside the launcher activity's `onCreate` that is
 * ~175-190 ms of disk read *per call site* on a cold emulator (StrictMode showed it), all before the
 * first frame, which is what turns a busy device's launch into an ANR. Calling them once from a
 * worker thread at process start loads the prefs file into the process-wide cache and warms the
 * directory entries, so the activity's later calls return immediately.
 */
object StartupWarmup {
    private val worker: Executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "volt-startup-warmup").apply { isDaemon = true }
        }

    /** Queues the warm-up for [context]'s process; returns at once. */
    fun start(context: Context) {
        val app = context.applicationContext ?: context
        start(worker) { warm(app) }
    }

    internal fun start(
        executor: Executor,
        task: () -> Unit,
    ) {
        try {
            executor.execute(task)
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            // Warm-up is only an optimisation; the activity still works without it.
            Log.w(AppPrefs.LOG_TAG, "startup warm-up not scheduled", ex)
        }
    }

    internal fun warm(context: Context) {
        try {
            context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).all
            context.filesDir
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "startup warm-up failed; continuing without it", ex)
        }
    }
}
