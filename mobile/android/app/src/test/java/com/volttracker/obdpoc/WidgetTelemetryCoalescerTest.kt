package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

/**
 * Pure-JVM tests for [WidgetTelemetryCoalescer] (audit item B5): the last-wins coalescing window,
 * the mid-drain re-schedule, and the rejected-schedule recovery, driven through a deterministic
 * hand-cranked [WidgetTelemetryCoalescer.Scheduler] instead of a real executor.
 */
class WidgetTelemetryCoalescerTest {
    private val scheduledTasks = ArrayDeque<Runnable>()
    private val scheduledDelays = ArrayList<Long>()
    private val delivered = ArrayList<JSONObject>()

    private fun newCoalescer(
        scheduler: WidgetTelemetryCoalescer.Scheduler =
            WidgetTelemetryCoalescer.Scheduler { task, delayMs ->
                scheduledTasks += task
                scheduledDelays += delayMs
            },
        onRejected: (RejectedExecutionException) -> Unit = {},
    ): WidgetTelemetryCoalescer = WidgetTelemetryCoalescer(COALESCE_MS, scheduler, delivered::add, onRejected)

    private fun runNextScheduledTask() {
        scheduledTasks.removeFirst().run()
    }

    @Test
    fun firstSubmitSchedulesOneDrainWithTheCoalesceDelay() {
        val coalescer = newCoalescer()

        coalescer.submit(payload("a"))

        assertEquals("one drain must be scheduled", 1, scheduledTasks.size)
        assertEquals(listOf(COALESCE_MS), scheduledDelays)
        assertTrue("nothing is delivered before the drain runs", delivered.isEmpty())
    }

    @Test
    fun samplesInsideTheWindowCoalesceIntoOneDeliveryOfTheNewestPayload() {
        val coalescer = newCoalescer()
        val first = payload("a")
        val second = payload("b")
        val third = payload("c")

        coalescer.submit(first)
        coalescer.submit(second)
        coalescer.submit(third)

        assertEquals("later samples must ride the pending drain, not schedule new ones", 1, scheduledTasks.size)
        assertEquals("both later samples count as coalesced", 2L, coalescer.drainCoalescedCountForTest())

        runNextScheduledTask()

        assertEquals("one coalesced delivery", 1, delivered.size)
        assertSame("the delivery must carry the newest payload", third, delivered[0])
        assertTrue("the drain must not leave another task scheduled", scheduledTasks.isEmpty())
        assertEquals("the counter was drained above", 0L, coalescer.drainCoalescedCountForTest())
    }

    @Test
    fun submitAfterADrainSchedulesAFreshWindow() {
        val coalescer = newCoalescer()
        coalescer.submit(payload("a"))
        runNextScheduledTask()

        val next = payload("b")
        coalescer.submit(next)

        assertEquals("a post-drain sample opens a new window", 1, scheduledTasks.size)
        runNextScheduledTask()
        assertEquals(2, delivered.size)
        assertSame(next, delivered[1])
    }

    @Test
    fun sampleArrivingDuringDeliveryIsRescheduledNotStranded() {
        // Models the race the mailbox re-check exists for: a sample lands after the drain has
        // taken the mailbox contents but before it releases the scheduled flag. Delivering the
        // first payload triggers the submit here, exactly at that point in drainLatest().
        lateinit var coalescer: WidgetTelemetryCoalescer
        val lateSample = payload("late")
        var injected = false
        coalescer =
            WidgetTelemetryCoalescer(
                COALESCE_MS,
                { task, _ -> scheduledTasks += task },
                { p ->
                    delivered += p
                    if (!injected) {
                        injected = true
                        coalescer.submit(lateSample)
                    }
                },
            )

        coalescer.submit(payload("a"))
        runNextScheduledTask()

        assertEquals("the mid-drain sample must be rescheduled by the mailbox re-check", 1, scheduledTasks.size)
        runNextScheduledTask()
        assertEquals(2, delivered.size)
        assertSame("the stranded sample must eventually deliver", lateSample, delivered[1])
        assertTrue(scheduledTasks.isEmpty())
    }

    @Test
    fun rejectedScheduleReportsAndRecoversForTheNextSubmit() {
        var rejectNext = true
        val rejections = ArrayList<RejectedExecutionException>()
        val coalescer =
            newCoalescer(
                scheduler =
                    WidgetTelemetryCoalescer.Scheduler { task, delayMs ->
                        if (rejectNext) {
                            throw RejectedExecutionException("executor shutting down")
                        }
                        scheduledTasks += task
                        scheduledDelays += delayMs
                    },
                onRejected = rejections::add,
            )

        coalescer.submit(payload("a"))
        assertEquals("the rejection must be surfaced to the owner", 1, rejections.size)
        assertTrue("no task must be scheduled on rejection", scheduledTasks.isEmpty())

        rejectNext = false
        val recovered = payload("b")
        coalescer.submit(recovered)
        assertEquals("a rejection must not wedge the scheduled flag", 1, scheduledTasks.size)
        runNextScheduledTask()
        assertEquals(listOf(recovered), delivered)
    }

    private companion object {
        private const val COALESCE_MS = 500L

        private fun payload(tag: String): JSONObject = JSONObject().put("tag", tag)
    }
}
