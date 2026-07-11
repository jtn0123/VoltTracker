package com.volttracker.obdpoc

import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardErrorSurfaceTest {
    private val container = FrameLayout(RuntimeEnvironment.getApplication())
    private var retryCount = 0
    private val surface = DashboardErrorSurface(container) { retryCount += 1 }

    @Test
    fun showMountsThePanelWithMessageAndRetry() {
        assertFalse(surface.isShowing())

        surface.show(R.string.dashboard_failed_repeatedly, true)

        assertTrue(surface.isShowing())
        val message = container.findViewById<TextView>(R.id.dashboard_error_message)
        assertEquals(
            container.context.getString(R.string.dashboard_failed_repeatedly),
            message.text.toString(),
        )
        val retry = container.findViewById<Button>(R.id.dashboard_error_retry)
        assertEquals(View.VISIBLE, retry.visibility)
        assertEquals(container.context.getString(R.string.dashboard_retry), retry.text.toString())
    }

    @Test
    fun reconnectingStateHidesTheRetryButton() {
        surface.show(R.string.dashboard_reconnecting, false)

        assertTrue(surface.isShowing())
        assertEquals(
            View.GONE,
            container.findViewById<Button>(R.id.dashboard_error_retry).visibility,
        )
    }

    @Test
    fun showTwiceReusesTheSamePanelAndUpdatesIt() {
        surface.show(R.string.dashboard_reconnecting, false)
        surface.show(R.string.dashboard_failed_repeatedly, true)

        assertEquals(
            "re-show must update the mounted panel, not stack a second one",
            1,
            container.childCount,
        )
        assertEquals(
            container.context.getString(R.string.dashboard_failed_repeatedly),
            container.findViewById<TextView>(R.id.dashboard_error_message).text.toString(),
        )
        assertEquals(
            View.VISIBLE,
            container.findViewById<Button>(R.id.dashboard_error_retry).visibility,
        )
    }

    @Test
    fun retryTapRunsTheCallback() {
        surface.show(R.string.dashboard_failed_repeatedly, true)

        container.findViewById<Button>(R.id.dashboard_error_retry).performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun hideUnmountsThePanelAndIsIdempotent() {
        surface.show(R.string.dashboard_reconnecting, false)

        surface.hide()
        surface.hide()

        assertFalse(surface.isShowing())
        assertEquals(0, container.childCount)

        // A later failure can re-show the same surface.
        surface.show(R.string.dashboard_failed_repeatedly, true)
        assertTrue(surface.isShowing())
    }

    @Test
    fun hideBeforeAnyShowIsSafe() {
        surface.hide()

        assertFalse(surface.isShowing())
    }
}
