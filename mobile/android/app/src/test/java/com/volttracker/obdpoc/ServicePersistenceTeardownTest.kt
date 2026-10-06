package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.service.ServicePersistenceTeardown
import com.volttracker.obdpoc.service.SessionRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServicePersistenceTeardownTest {
    @Test
    fun survivingWriterKeepsDatabaseAndRestoreGuardOpen() {
        val closed = AtomicBoolean(false)
        val store =
            object : ObdLocalStore(RuntimeEnvironment.getApplication()) {
                override fun close() {
                    closed.set(true)
                }
            }
        val owner = requireNotNull(DatabaseOperationLease.tryRegisterPersistence())
        val runner = Executors.newSingleThreadExecutor()
        val sideEffects = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        runner.submit {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (ignored: InterruptedException) {
                    // Deliberately stubborn writer.
                }
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        runner.shutdown()
        sideEffects.shutdown()
        val logs = File(RuntimeEnvironment.getApplication().cacheDir, "teardown-${System.nanoTime()}")
        val recorder = SessionRecorder(Any(), ObdSessionLog(logs), store)
        val teardown = ServicePersistenceTeardown(drainTimeoutMs = 10, forceTimeoutMs = 10)
        try {
            teardown.start(recorder, store, owner, listOf(runner, sideEffects))
            assertTrue(teardown.await(2_000))
            assertFalse("SQLite must not close underneath a surviving writer", closed.get())
            assertFalse("Restore must fail closed", DatabaseOperationLease.awaitPersistenceQuiescence(10))
        } finally {
            release.countDown()
            assertTrue(runner.awaitTermination(2, TimeUnit.SECONDS))
            recorder.shutdown()
            store.close()
            owner.close()
        }
    }
}
