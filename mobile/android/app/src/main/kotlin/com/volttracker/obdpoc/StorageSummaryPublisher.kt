package com.volttracker.obdpoc

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coalesces and throttles storage-summary reads before publishing them to the dashboard.
 *
 * The Activity owns the actual store read and WebView publish call; this helper owns only the
 * concurrency bookkeeping so MainActivity does not carry the in-flight/dirty/queued state itself.
 */
internal class StorageSummaryPublisher(
    private val submitBackground: (Runnable) -> Unit,
    private val runOnUi: (Runnable) -> Unit,
    private val readStorageJson: () -> String,
    private val publishStorageJson: (String, JSONObject) -> Unit,
) {
    private val inFlight = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)
    private val lastPublishedAtMs = AtomicLong(0L)
    private val dirty = AtomicBoolean(true)

    fun publishThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastPublishedAtMs.get() < STORAGE_SUMMARY_MIN_INTERVAL_MS) {
            return
        }
        if (!dirty.get()) {
            return
        }
        publish()
    }

    fun markDirty() {
        dirty.set(true)
    }

    fun publish() {
        if (!inFlight.compareAndSet(false, true)) {
            queued.set(true)
            return
        }
        runRefresh()
    }

    private fun runRefresh() {
        submitBackground(
            Runnable {
                dirty.set(false)
                val storage = readStorageJson()
                val parsed = MainActivityUtils.parseJson(storage)
                if (!parsed.optBoolean("ok", true)) {
                    dirty.set(true)
                }
                lastPublishedAtMs.set(System.currentTimeMillis())
                runOnUi(
                    Runnable {
                        publishStorageJson(storage, parsed)
                    },
                )
                inFlight.set(false)
                if (queued.getAndSet(false)) {
                    publish()
                }
            },
        )
    }

    private companion object {
        const val STORAGE_SUMMARY_MIN_INTERVAL_MS = 1_500L
    }
}
