package com.volttracker.obdpoc

/**
 * Token-bucket rate limiter for the dashboard's `logClientError` firehose. A runaway page (an error
 * loop, a flood of CSP violations) must not spam logcat, so the bridge takes a token per call and
 * drops the line when the bucket is empty, folding the dropped count into the next admitted line.
 *
 * Extracted off [VoltBridge] so its self-contained throttle state (the bucket + drop counter) and
 * the refill arithmetic live as their own unit-testable concern rather than swelling the bridge's
 * function surface — the same "decompose the bridge" direction ADR 0007's detekt note points at.
 *
 * Thread-safe: the WebView JavaBridge thread is the only caller, but [acquireOrCountDrop] still
 * guards the read-modify-write under a lock so it is safe if that ever changes.
 */
class ClientErrorRateLimiter(
    private val burst: Int = DEFAULT_BURST,
    private val refillPerSec: Int = DEFAULT_REFILL_PER_SEC,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var tokens = burst.toDouble()

    // Nullable rather than a 0L sentinel: an injectable clock can legitimately return 0 (and the
    // process clock does at the unix epoch), so a magic-zero "uninitialized" marker would treat
    // every call at time 0 as the first and never accrue elapsed time. Null is the unambiguous
    // "no refill baseline yet" state.
    private var lastRefillMs: Long? = null
    private var droppedCount = 0L

    /**
     * Takes a token. Returns -1 when the bucket is empty (the caller drops the line), otherwise the
     * number of previously-dropped lines to fold into this admitted line (0 when none were dropped).
     */
    fun acquireOrCountDrop(): Long =
        synchronized(lock) {
            val now = clock()
            val elapsed = maxOf(0L, now - (lastRefillMs ?: now))
            if (elapsed > 0L) {
                val refill = elapsed / 1000.0 * refillPerSec
                tokens = minOf(burst.toDouble(), tokens + refill)
            }
            // Record the baseline on the first call and advance it whenever time moves forward, so a
            // later call accrues refill from here. A same-instant repeat call keeps the baseline.
            lastRefillMs = now
            if (tokens < 1.0) {
                droppedCount++
                -1L
            } else {
                tokens -= 1.0
                val dropped = droppedCount
                droppedCount = 0L
                dropped
            }
        }

    companion object {
        const val DEFAULT_REFILL_PER_SEC = 5
        const val DEFAULT_BURST = 10
    }
}
