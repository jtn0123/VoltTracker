package com.volttracker.obdpoc

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** Process-wide exclusion between database snapshots/restores and new logging sessions. */
object DatabaseOperationLease {
    private val active = AtomicReference<Token?>(null)
    private val monitor = Object()
    private val persistenceOwners = mutableSetOf<PersistenceOwner>()

    fun tryAcquire(operation: String): Token? {
        val token = Token(operation)
        return synchronized(monitor) { if (active.compareAndSet(null, token)) token else null }
    }

    fun isHeld(): Boolean = active.get() != null

    /** Register before a service can open SQLite; a restore lease prevents new owners. */
    fun tryRegisterPersistence(): PersistenceOwner? =
        synchronized(monitor) {
            if (isHeld()) null else PersistenceOwner().also { persistenceOwners.add(it) }
        }

    fun hasPersistenceOwners(): Boolean = synchronized(monitor) { persistenceOwners.isNotEmpty() }

    /** Called by the restore worker, after stopping the service. Failed drains stay fail-closed. */
    fun awaitPersistenceQuiescence(
        timeoutMs: Long,
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(timeoutMs)
        synchronized(monitor) {
            while (persistenceOwners.isNotEmpty()) {
                if (cancelled()) return false
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    monitor.wait(
                        minOf(
                            50L,
                            java.util.concurrent.TimeUnit.NANOSECONDS
                                .toMillis(remaining)
                                .coerceAtLeast(1L),
                        ),
                    )
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return !cancelled()
        }
    }

    class PersistenceOwner internal constructor() : Closeable {
        /** Release only after every writer terminates and the service's SQLite handle closes. */
        override fun close() {
            synchronized(monitor) {
                persistenceOwners.remove(this)
                monitor.notifyAll()
            }
        }
    }

    class Token internal constructor(
        val operation: String,
    ) : Closeable {
        override fun close() {
            active.compareAndSet(this, null)
        }
    }
}
