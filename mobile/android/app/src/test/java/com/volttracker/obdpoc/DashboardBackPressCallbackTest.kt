package com.volttracker.obdpoc

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Covers the Back-press hand-off between the OS and the dashboard SPA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardBackPressCallbackTest {
    @Test
    fun missingWebViewFallsStraightBackToTheOs() {
        var exits = 0
        val callback = DashboardBackPressCallback({ null }, { exits += 1 })

        callback.handleOnBackPressed()

        assertEquals(1, exits)
    }

    @Test
    fun webViewGetsFirstCrackViaTheBackProbe() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        var exits = 0
        val callback = DashboardBackPressCallback({ webView }, { exits += 1 })

        callback.handleOnBackPressed()

        val script = shadowOf(webView).lastEvaluatedJavascript
        assertEquals(DashboardBackPressCallback.BACK_PROBE_JS, script)
        assertTrue(
            "the probe must call the dashboard's Back handler",
            script.contains("VoltDashboard.handleAndroidBack"),
        )
        assertEquals("Back must not exit before the dashboard answers", 0, exits)
    }

    @Test
    fun callbackStartsEnabledSoItInterceptsSystemBack() {
        assertTrue(DashboardBackPressCallback({ null }, {}).isEnabled)
    }
}
