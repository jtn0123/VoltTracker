package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Closes the Activity-owned store only after every background database user has stopped. */
internal object ActivityStoreTeardown {
    fun closeWhenExecutorStops(
        executor: ExecutorService,
        store: ObdLocalStore?,
    ) {
        store ?: return
        val terminated =
            try {
                executor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (terminated) {
            closeSafely(store)
            return
        }

        // SQLiteOpenHelper.close() must not race a backup, restore, or dashboard read that ignored
        // interruption. This daemon owns only the executor + store, not the destroyed Activity.
        Log.w(AppPrefs.LOG_TAG, "background worker survived Activity teardown; deferring SQLite close")
        Thread(
            {
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                    closeSafely(store)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(AppPrefs.LOG_TAG, "deferred SQLite close interrupted", ex)
                }
            },
            "volttracker-store-close",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun closeSafely(store: ObdLocalStore) {
        try {
            store.close()
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "local store close failed during Activity teardown", ex)
        }
    }

    private const val SHUTDOWN_GRACE_MS = 100L
}
