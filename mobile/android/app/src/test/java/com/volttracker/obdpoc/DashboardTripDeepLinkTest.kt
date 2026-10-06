package com.volttracker.obdpoc

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardTripDeepLinkTest {
    private var ready = false
    private val trips = mutableListOf<Pair<String, Boolean>>()
    private val views = mutableListOf<String>()
    private val link = DashboardTripDeepLink({ ready }, { key, receipt -> trips += key to receipt }, { views += it })

    @Test
    fun aTabLinkWaitsForThePageThenOpensOnce() {
        link.capture(Intent().putExtra(MainActivity.EXTRA_OPEN_VIEW, "insights"))
        link.publishPending()
        assertTrue("nothing is sent before the page is ready", views.isEmpty())

        ready = true
        link.publishPending()
        link.publishPending()
        assertEquals(listOf("insights"), views)
        assertTrue(trips.isEmpty())
    }

    @Test
    fun unknownTabsAreIgnored() {
        ready = true
        link.capture(Intent().putExtra(MainActivity.EXTRA_OPEN_VIEW, "alert(1)"))
        link.publishPending()
        assertTrue(views.isEmpty())
    }

    @Test
    fun troubleshootingHasItsOwnDestinationAndWaitsForPageReadiness() {
        var opened = 0
        val help = DashboardTripDeepLink({ ready }, { _, _ -> }, { views += it }, { opened++ })
        help.capture(Intent().putExtra(MainActivity.EXTRA_OPEN_TROUBLESHOOTER, true))
        help.publishPending()
        assertEquals(0, opened)
        ready = true
        help.publishPending()
        help.publishPending()
        assertEquals(1, opened)
        assertTrue(views.isEmpty())
    }

    @Test
    fun aDriveLinkOpensItsReceipt() {
        ready = true
        link.capture(
            Intent()
                .putExtra(MainActivity.EXTRA_OPEN_TRIP, "route-7")
                .putExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, true),
        )
        link.publishPending()
        assertEquals(listOf("route-7" to true), trips)
    }
}
