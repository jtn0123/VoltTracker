package com.volttracker.obdpoc.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.car.BodyFocusPinger
import com.volttracker.obdpoc.ui.car.BodyFocusPinger.Companion.LEASE_MS
import com.volttracker.obdpoc.ui.car.BodyFocusPinger.Companion.RENEW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The Car tab's body-bus lease: renewed while the tab is in front, ended when it isn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BodyFocusPingerTest {
    private val sent = mutableListOf<Long>()
    private var session = true
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private val scheduler =
        object : BodyFocusPinger.Scheduler {
            override fun schedule(
                delayMs: Long,
                task: Runnable,
            ) {
                scheduled += delayMs to task
            }

            override fun cancel(task: Runnable) {
                scheduled.removeAll { it.second === task }
            }
        }
    private val pinger = BodyFocusPinger({ sent += it }, { session }, scheduler)

    private fun renew() = scheduled.removeAt(0).second.run()

    @Test
    fun theCarTabRenewsTheLeaseWhileItIsInFront() {
        pinger.setResumed(true)
        assertTrue("another tab: nothing sent", sent.isEmpty())
        pinger.setCarTabShown(true)
        assertEquals(listOf(LEASE_MS), sent)
        assertEquals(listOf(RENEW_MS), scheduled.map { it.first })
        renew()
        assertEquals(listOf(LEASE_MS, LEASE_MS), sent)
        assertEquals("one renewal pending at a time", 1, scheduled.size)
        pinger.setCarTabShown(false)
        assertEquals(listOf(LEASE_MS, LEASE_MS, 0L), sent)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun leavingTheScreenEndsTheLeaseAndComingBackRenewsIt() {
        pinger.setCarTabShown(true)
        assertTrue("the screen isn't in front yet", sent.isEmpty())
        pinger.setResumed(true)
        pinger.setResumed(false)
        assertEquals(listOf(LEASE_MS, 0L), sent)
        assertTrue(scheduled.isEmpty())
        pinger.setResumed(true)
        assertEquals(listOf(LEASE_MS, 0L, LEASE_MS), sent)
    }

    @Test
    fun withoutASessionNothingIsSentButTheTabKeepsChecking() {
        session = false
        pinger.setResumed(true)
        pinger.setCarTabShown(true)
        assertTrue("opening the tab never starts the service", sent.isEmpty())
        assertEquals("it checks again for a session that starts", 1, scheduled.size)
        session = true
        renew()
        assertEquals(listOf(LEASE_MS), sent)
        session = false
        pinger.setCarTabShown(false)
        assertEquals("the session ended: there is no one to tell", listOf(LEASE_MS), sent)
    }

    @Test
    fun noStopIsSentWhenNoLeaseWasTaken() {
        pinger.setResumed(true)
        pinger.setResumed(false)
        pinger.setCarTabShown(false)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theServicePingerAsksObdServiceForTheLeaseOnTheMainThread() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val real = BodyFocusPinger.forService(app) { true }
        real.setResumed(true)
        real.setCarTabShown(true)
        val first = shadowOf(app).nextStartedService
        assertEquals(ObdService.ACTION_BODY_FOCUS, first.action)
        assertEquals(LEASE_MS, first.getLongExtra(ObdService.EXTRA_DURATION_MS, -1L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RENEW_MS))
        assertEquals(
            "renewed",
            LEASE_MS,
            shadowOf(app).nextStartedService.getLongExtra(ObdService.EXTRA_DURATION_MS, -1L),
        )
        real.setCarTabShown(false)
        assertEquals(0L, shadowOf(app).nextStartedService.getLongExtra(ObdService.EXTRA_DURATION_MS, -1L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RENEW_MS * 2))
        assertNull("no renewal after the tab closed", shadowOf(app).nextStartedService)
    }
}
