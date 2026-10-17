package com.volttracker.obdpoc

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawDataMaintenanceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("maintenance-test", Context.MODE_PRIVATE)
    private val scheduled = ArrayDeque<Runnable>()
    private val workers = ArrayDeque<Runnable>()
    private var now = 1_000_000L
    private val retention = ArrayList<Int>()

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    private fun coordinator(maintain: (Int) -> Unit = { retention.add(it) }) =
        RawDataMaintenance(
            prefs = { prefs },
            maintain = maintain,
            schedule = { scheduled.add(it) },
            worker = Executor { workers.add(it) },
            nowMs = { now },
        )

    private fun drain() {
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        while (workers.isNotEmpty()) workers.removeFirst().run()
    }

    @Test fun nativeLaunchSchedulesCoalescedWorkOnTheWorkerAndPersistsTheDailyThrottle() {
        val coordinator = coordinator()
        app.registerActivityLifecycleCallbacks(coordinator)
        val activity = Robolectric.buildActivity(ComposeDashboardActivity::class.java).create().start()
        try {
            coordinator.request()
            assertEquals(1, scheduled.size)
            assertTrue(retention.isEmpty())
            scheduled.removeFirst().run()
            assertTrue("scheduling never opens SQLite on the lifecycle thread", retention.isEmpty())
            assertEquals(1, workers.size)
            workers.removeFirst().run()
            assertEquals(listOf(60), retention)
            assertEquals(now, prefs.getLong(RawDataMaintenance.LAST_SUCCESS, 0))
            val recreated = coordinator()
            recreated.request()
            drain()
            assertEquals("a new coordinator also honors the saved timestamp", 1, retention.size)
            now += RawDataMaintenance.INTERVAL_MS
            recreated.request()
            drain()
            assertEquals(2, retention.size)
        } finally {
            app.unregisterActivityLifecycleCallbacks(coordinator)
            activity.stop().destroy()
        }
    }

    @Test fun backupRestoreAndRecordingDeferMaintenanceWithoutConsumingTheDailyAttempt() {
        val coordinator = coordinator()
        for (operation in listOf("backup", "restore")) {
            DatabaseOperationLease.tryAcquire(operation)!!.use {
                coordinator.request()
                drain()
            }
        }
        DatabaseOperationLease.tryRegisterPersistence()!!.use {
            coordinator.request()
            drain()
        }
        assertTrue(retention.isEmpty())
        assertFalse(prefs.contains(RawDataMaintenance.LAST_SUCCESS))
        coordinator.request()
        drain()
        assertEquals(listOf(60), retention)
    }

    @Test fun theLeaseCoversTheWholeMaintenanceAndFailuresReleaseItForRetry() {
        var fail = true
        val coordinator =
            coordinator {
                assertTrue(DatabaseOperationLease.isHeld())
                assertNull(DatabaseOperationLease.tryRegisterPersistence())
                assertNull(DatabaseOperationLease.tryAcquire("backup"))
                if (fail) throw IllegalStateException("disk full")
                retention.add(it)
            }
        coordinator.request()
        drain()
        assertFalse(DatabaseOperationLease.isHeld())
        assertFalse(prefs.contains(RawDataMaintenance.LAST_SUCCESS))
        fail = false
        coordinator.request()
        drain()
        assertEquals(listOf(60), retention)
    }

    @Test fun selectedRetentionAndDisabledRetentionAreRespected() {
        prefs.edit().putInt("raw_retention_days", 90).commit()
        coordinator().request()
        drain()
        assertEquals(listOf(90), retention)
        now += RawDataMaintenance.INTERVAL_MS
        prefs.edit().putInt("raw_retention_days", 0).commit()
        coordinator().request()
        drain()
        assertEquals(listOf(90), retention)
    }

    @Test fun rejectedWorkerCanBeRetried() {
        var reject = true
        val coordinator =
            RawDataMaintenance(
                prefs = { prefs },
                maintain = { retention.add(it) },
                schedule = { scheduled.add(it) },
                worker = Executor { if (reject) throw RejectedExecutionException() else workers.add(it) },
                nowMs = { now },
            )
        coordinator.request()
        drain()
        reject = false
        coordinator.request()
        drain()
        assertEquals(listOf(60), retention)
    }
}
