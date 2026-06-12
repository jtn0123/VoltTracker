package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A throwing [ObdLocalStore] constructor (corrupt DB file, full disk) must not crash startup:
 * onCreate leaves the store null — every store access already tolerates that — and the dashboard
 * handshake reports the failure instead of pretending local data is viewable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityStoreInitFailureTest {
    @Test
    fun storeOpenFailureLeavesStoreNullAndSurfacesBlockedStatus() {
        val controller = Robolectric.buildActivity(StoreFailureActivity::class.java).create()
        try {
            val activity = controller.get()
            assertNull("a failed store open must leave localStore null", activity.localStore)

            activity.onDashboardReady()

            assertEquals("the handshake must surface the storage failure", "blocked", activity.lastStatusState)
            assertTrue("the storage-failure status must be blocking", activity.lastStatusBlocked)
        } finally {
            controller.destroy()
        }
    }

    class StoreFailureActivity : MainActivity() {
        var lastStatusState: String? = null
        var lastStatusBlocked = false

        override fun createLocalStore(): ObdLocalStore = throw RuntimeException("test: store open failed")

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusBlocked = blocked
        }
    }
}
