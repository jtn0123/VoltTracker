package com.volttracker.obdpoc

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** Process-wide exclusion between database snapshots/restores and new logging sessions. */
object DatabaseOperationLease {
    private val active = AtomicReference<Token?>(null)

    fun tryAcquire(operation: String): Token? {
        val token = Token(operation)
        return if (active.compareAndSet(null, token)) token else null
    }

    fun isHeld(): Boolean = active.get() != null

    class Token internal constructor(
        val operation: String,
    ) : Closeable {
        override fun close() {
            active.compareAndSet(this, null)
        }
    }
}
