package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

/**
 * Regression guard for the "black screen on resume" report: the Activity must drive the WebView's
 * own lifecycle (onPause/onResume) in lockstep with its own. Without these calls chromium leaves the
 * compositor surface suspended after a background -> foreground trip and the page repaints black.
 *
 * Robolectric's [ShadowWebView] records whether onPause()/onResume() were invoked, so we can assert
 * the wiring through the real Activity lifecycle instead of relying on the emulator smoke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWebViewLifecycleTest {
    @org.junit.Before
    fun forceNativeSqliteLoad() {
        // Bind Robolectric's native SQLite runtime up front so the Activity's onCreate DB open does
        // not race a later SQLite test into UnsatisfiedLinkError (same guard as
        // MainActivityDashboardReadyTest).
        ObdLocalStore(RuntimeEnvironment.getApplication()).apply {
            getRecentSessions(1)
            close()
        }
    }

    @Test
    fun resumeAndPauseDriveTheWebViewLifecycle() {
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = requireNotNull(activity.webViewForTest()) { "onCreate must create the WebView" }
        val shadow: ShadowWebView = shadowOf(webView)

        // setup() runs create -> start -> resume; onResume() must have resumed the WebView.
        assertTrue("onResume must resume the WebView renderer", shadow.wasOnResumeCalled())
        assertFalse("the WebView is not paused while the Activity is resumed", shadow.wasOnPauseCalled())

        controller.pause()
        assertTrue("onPause must pause the WebView renderer", shadow.wasOnPauseCalled())

        controller.destroy()
    }
}
