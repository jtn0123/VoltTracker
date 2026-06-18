package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DeviceListPublisherTest {
    @Test
    fun publishReadsOffUiThreadThenPublishesOnUiThread() {
        val backgroundTasks = ArrayList<Runnable>()
        val uiTasks = ArrayList<Runnable>()
        val published = ArrayList<DeviceListPayload>()
        var reads = 0
        val publisher =
            DeviceListPublisher(
                submitBackground = { backgroundTasks.add(it) },
                runOnUi = { uiTasks.add(it) },
                readDeviceJson = {
                    reads += 1
                    DeviceListPayload("[{\"address\":\"AA\"}]", "[{\"address\":\"AA\",\"connectCount\":1}]")
                },
                publishDeviceJson = { published.add(it) },
            )

        publisher.publish()

        assertEquals(1, backgroundTasks.size)
        assertEquals(0, reads)
        assertEquals(0, uiTasks.size)

        backgroundTasks.removeAt(0).run()
        assertEquals(1, reads)
        assertEquals(1, uiTasks.size)
        assertEquals(0, published.size)

        uiTasks.removeAt(0).run()
        assertEquals(
            DeviceListPayload("[{\"address\":\"AA\"}]", "[{\"address\":\"AA\",\"connectCount\":1}]"),
            published.single(),
        )
    }

    @Test
    fun publishCoalescesWhileAReadIsInFlight() {
        val backgroundTasks = ArrayList<Runnable>()
        val uiTasks = ArrayList<Runnable>()
        val published = ArrayList<DeviceListPayload>()
        var reads = 0
        val publisher =
            DeviceListPublisher(
                submitBackground = { backgroundTasks.add(it) },
                runOnUi = { uiTasks.add(it) },
                readDeviceJson = {
                    reads += 1
                    DeviceListPayload("[{\"address\":\"$reads\"}]", "[]")
                },
                publishDeviceJson = { published.add(it) },
            )

        publisher.publish()
        publisher.publish()
        publisher.publish()

        assertEquals(1, backgroundTasks.size)
        backgroundTasks.removeAt(0).run()
        assertEquals("queued publish should schedule one follow-up read", 1, backgroundTasks.size)
        backgroundTasks.removeAt(0).run()

        assertEquals(2, reads)
        assertEquals(2, uiTasks.size)
        uiTasks.removeAt(0).run()
        uiTasks.removeAt(0).run()

        assertEquals(
            listOf(DeviceListPayload("[{\"address\":\"1\"}]", "[]"), DeviceListPayload("[{\"address\":\"2\"}]", "[]")),
            published,
        )
    }

    @Test
    fun publishReleasesInFlightWhenReadThrows() {
        val backgroundTasks = ArrayList<Runnable>()
        var reads = 0
        val publisher =
            DeviceListPublisher(
                submitBackground = { backgroundTasks.add(it) },
                runOnUi = { fail("UI publish should not run after a read failure") },
                readDeviceJson = {
                    reads += 1
                    throw IllegalStateException("read failed")
                },
                publishDeviceJson = { fail("payload should not publish after a read failure") },
            )

        publisher.publish()
        try {
            backgroundTasks.removeAt(0).run()
            fail("expected the read failure to propagate to the worker")
        } catch (ex: IllegalStateException) {
            assertEquals("read failed", ex.message)
        }

        publisher.publish()

        assertEquals("the in-flight latch must release after a failed worker run", 1, backgroundTasks.size)
        assertEquals(1, reads)
    }
}
