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

    // maintain stays last so a trailing lambda still means the maintenance body.
    private fun coordinator(
        onPassEnd: () -> Unit = {},
        acquireLease: () -> DatabaseOperationLease.Token? = {
            DatabaseOperationLease.tryAcquireWhenIdle("raw maintenance")
        },
        maintain: (Int) -> Unit = { retention.add(it) },
    ) = RawDataMaintenance(
        prefs = { prefs },
        maintain = maintain,
        schedule = { scheduled.add(it) },
        worker = Executor { workers.add(it) },
        nowMs = { now },
        onPassEnd = onPassEnd,
        acquireLease = acquireLease,
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

    // The service refuses every session start while the lease is held, and every Activity start and
    // stop requests a pass, so only a pass that will prune may take it.
    @Test fun aPassThatIsNotDueNeverTakesTheLease() {
        var acquisitions = 0
        val coordinator =
            coordinator(acquireLease = {
                acquisitions += 1
                DatabaseOperationLease.tryAcquireWhenIdle("raw maintenance")
            })

        coordinator.request()
        drain()
        assertEquals(1, acquisitions)
        repeat(3) {
            coordinator.request()
            drain()
        }
        assertEquals("passes inside the daily window never ask for the lease", 1, acquisitions)
        now += RawDataMaintenance.INTERVAL_MS
        coordinator.request()
        drain()
        assertEquals(2, acquisitions)
        assertEquals(listOf(60, 60), retention)
    }

    @Test fun theLeaseCoversTheWholeMaintenanceAndFailuresReleaseItForRetry() {
        var fail = true
        val coordinator =
            coordinator {
                assertTrue(DatabaseOperationLease.isHeld())
                assertNull(DatabaseOperationLease.tryRegisterPersistence())
                assertNull(DatabaseOperationLease.tryAcquire("backup"))
                check(!fail) { "disk full" }
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

    // The emulator smoke starts its classic-phase demo only after this signal, because the service
    // refuses a session while the pass holds the lease. It must fire however the pass ends, and
    // only once the pass has let go of the lease.
    @Test fun everyPassSignalsItsEndAfterReleasingTheLease() {
        var fail = false
        val heldAtEnd = ArrayList<Boolean>()
        val coordinator =
            coordinator(
                maintain = {
                    check(!fail) { "disk full" }
                    retention.add(it)
                },
                onPassEnd = { heldAtEnd.add(DatabaseOperationLease.isHeld()) },
            )

        coordinator.request()
        coordinator.request()
        drain()
        assertEquals("a due pass, with a coalesced request, ends once", listOf(false), heldAtEnd)
        assertEquals(listOf(60), retention)

        coordinator.request()
        drain()
        assertEquals("a pass that is not due still ends", listOf(false, false), heldAtEnd)

        DatabaseOperationLease.tryRegisterPersistence()!!.use {
            coordinator.request()
            drain()
        }
        assertEquals("a pass that yields to a recording still ends", 3, heldAtEnd.size)

        now += RawDataMaintenance.INTERVAL_MS
        fail = true
        coordinator.request()
        drain()
        assertEquals("a failed pass ends after releasing the lease", listOf(false, false, false, false), heldAtEnd)
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
