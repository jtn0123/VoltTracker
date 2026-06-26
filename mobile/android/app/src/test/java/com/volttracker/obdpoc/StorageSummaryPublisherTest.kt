package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [StorageSummaryPublisher], focused on the trailing-flush behavior: a
 * dirty update that arrives inside the throttle window must still reach the dashboard via a single
 * scheduled flush rather than being dropped until some unrelated later call.
 */
class StorageSummaryPublisherTest {
    private class Harness {
        val backgroundTasks = ArrayList<Runnable>()
        val uiTasks = ArrayList<Runnable>()
        val scheduled = ArrayList<Pair<Long, Runnable>>()
        val published = ArrayList<String>()
        var reads = 0

        val publisher =
            StorageSummaryPublisher(
                submitBackground = { backgroundTasks.add(it) },
                runOnUi = { uiTasks.add(it) },
                readStorageJson = {
                    reads += 1
                    "{\"ok\":true,\"read\":$reads}"
                },
                publishStorageJson = { storage, _ -> published.add(storage) },
                scheduleDelayed = { delayMs, task -> scheduled.add(delayMs to task) },
            )

        /** Drains the background read + UI publish for one in-flight publish() cycle. */
        fun drainOnePublish() {
            while (backgroundTasks.isNotEmpty()) {
                backgroundTasks.removeAt(0).run()
            }
            while (uiTasks.isNotEmpty()) {
                uiTasks.removeAt(0).run()
            }
        }
    }

    @Test
    fun firstThrottledPublishGoesOutImmediately() {
        val h = Harness()

        h.publisher.publishThrottled()
        h.drainOnePublish()

        assertEquals("the first call publishes immediately", 1, h.published.size)
        assertTrue("no trailing flush needed for the leading edge", h.scheduled.isEmpty())
    }

    @Test
    fun dirtyUpdateInsideWindowSchedulesASingleTrailingFlushThatPublishes() {
        val h = Harness()

        // Leading-edge publish sets lastPublishedAt to "now" and clears dirty.
        h.publisher.publishThrottled()
        h.drainOnePublish()
        assertEquals(1, h.published.size)

        // A new mutation lands and triggers a throttled publish well within the 1.5s window.
        h.publisher.markDirty()
        h.publisher.publishThrottled()

        // It must not publish synchronously, but must arm exactly one trailing flush.
        assertEquals("no immediate second publish inside the window", 1, h.published.size)
        assertEquals("exactly one trailing flush armed", 1, h.scheduled.size)

        // A second throttled call in the same window must not stack another flush.
        h.publisher.publishThrottled()
        assertEquals("trailing flush is not stacked", 1, h.scheduled.size)

        // Fire the scheduled flush: the pending update now reaches the dashboard.
        h.scheduled
            .removeAt(0)
            .second
            .run()
        h.drainOnePublish()
        assertEquals("the trailing flush delivers the dropped update", 2, h.published.size)
    }

    @Test
    fun cleanStateInsideWindowArmsNoFlush() {
        val h = Harness()

        h.publisher.publishThrottled()
        h.drainOnePublish()

        // No markDirty(): a throttled call inside the window with nothing pending arms nothing.
        h.publisher.publishThrottled()
        assertTrue("no flush when there is nothing dirty to publish", h.scheduled.isEmpty())
    }
}
