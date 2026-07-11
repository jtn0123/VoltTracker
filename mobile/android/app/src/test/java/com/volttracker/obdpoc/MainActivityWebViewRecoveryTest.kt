package com.volttracker.obdpoc

import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.TestWebResourceError
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Integration coverage for report item B1: a dead WebView renderer must never take the app down
 * or leave a silent broken page. The recovery path tears the dead WebView down, mounts a fresh
 * one with the bridge re-wired (so telemetry resumes after the new handshake), and caps automatic
 * recreates before falling back to a persistent native error surface with Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWebViewRecoveryTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        // Bind Robolectric's native SQLite runtime up front (same guard as the other
        // MainActivity suites) so the Activity's onCreate DB open cannot race into
        // UnsatisfiedLinkError.
        ObdLocalStore(RuntimeEnvironment.getApplication()).apply {
            getRecentSessions(1)
            close()
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun currentWebView(): WebView = requireNotNull(activity.webViewForTest())

    private fun currentClient(): WebViewClient = shadowOf(currentWebView()).webViewClient

    private fun renderDeath(crashed: Boolean): RenderProcessGoneDetail =
        object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = crashed

            override fun rendererPriorityAtExit(): Int = 0
        }

    private fun killRenderer(crashed: Boolean = true): Boolean {
        val webView = currentWebView()
        return currentClient().onRenderProcessGone(webView, renderDeath(crashed))
    }

    private fun errorSurface(): View? = activity.findViewById(R.id.dashboard_error_surface)

    @Test
    fun rendererDeathIsConsumedAndTheWebViewIsRecreatedWithTheBridge() {
        val dead = currentWebView()

        val consumed = killRenderer(crashed = true)

        assertTrue("onRenderProcessGone must return true or Android kills the app", consumed)
        assertTrue("the dead WebView must be destroyed", shadowOf(dead).wasDestroyCalled())
        val replacement = currentWebView()
        assertNotSame("a fresh WebView must replace the dead one", dead, replacement)
        assertNotNull(
            "recovery must re-run the bootstrap wiring so the JS bridge is restored",
            shadowOf(replacement).getJavascriptInterface("VoltTrackerAndroid"),
        )
        assertEquals(
            "the fresh WebView must reload the dashboard",
            "file:///android_asset/dashboard/index.html",
            shadowOf(replacement).lastLoadedUrl,
        )
        assertEquals(
            "the recreated dashboard is loading again (macrobenchmark/a11y contract)",
            MainActivity.DASHBOARD_LOADING_DESCRIPTION,
            replacement.contentDescription,
        )
    }

    @Test
    fun rendererDeathShowsAReconnectingSurfaceUntilTheNewHandshakeLands() {
        killRenderer(crashed = false)

        val surface = errorSurface()
        assertNotNull("recovery must surface a reconnecting state", surface)
        assertEquals(
            "reconnecting is automatic; no Retry button while the reload is in flight",
            View.GONE,
            activity.findViewById<Button>(R.id.dashboard_error_retry).visibility,
        )

        activity.onDashboardReady()

        assertNull("the new page's handshake must clear the surface", errorSurface())
        assertTrue("the recovered dashboard is ready for publishes", activity.isDashboardReadyForTest())
    }

    @Test
    fun repeatedRendererDeathsHitTheCapAndShowThePersistentRetrySurface() {
        repeat(DashboardRecoveryCoordinator.MAX_AUTO_RECOVERIES) {
            killRenderer(crashed = true)
        }
        // The cap is exhausted: this death must not spin up another WebView.
        killRenderer(crashed = true)

        assertNull("after the cap the WebView stays down", activity.webViewForTest())
        val retry = activity.findViewById<Button>(R.id.dashboard_error_retry)
        assertNotNull("the persistent surface must be mounted", errorSurface())
        assertEquals("the persistent surface offers Retry", View.VISIBLE, retry.visibility)

        retry.performClick()

        assertNotNull("user Retry must rebuild the WebView", activity.webViewForTest())
        assertNull("Retry clears the error surface", errorSurface())
        assertNotNull(
            shadowOf(currentWebView()).getJavascriptInterface("VoltTrackerAndroid"),
        )
    }

    @Test
    fun staleRendererDeathFromAReplacedWebViewIsIgnored() {
        val original = currentWebView()
        val originalClient = currentClient()
        killRenderer(crashed = true)
        val replacement = currentWebView()

        // The dead WebView's client reports again (late callback): must not tear down the
        // healthy replacement.
        originalClient.onRenderProcessGone(original, renderDeath(true))

        assertEquals("a stale report must not replace the healthy WebView", replacement, currentWebView())
    }

    @Test
    fun mainFrameLoadErrorTriggersTheSameRecovery() {
        val dead = currentWebView()

        currentClient().onReceivedError(
            dead,
            resourceRequest(mainFrame = true),
            resourceError(-14, "net::ERR_FILE_NOT_FOUND"),
        )

        assertNotSame("a broken main frame must be replaced", dead, currentWebView())
        assertTrue(shadowOf(dead).wasDestroyCalled())
        assertNotNull("recovery surfaces the reconnecting state", errorSurface())
    }

    @Test
    fun subResourceErrorsDoNotTearDownThePage() {
        val webView = currentWebView()

        currentClient().onReceivedError(
            webView,
            resourceRequest(mainFrame = false),
            resourceError(-2, "net::ERR_NAME_NOT_RESOLVED"),
        )

        assertEquals("a sub-resource error is the page's problem, not a teardown", webView, currentWebView())
        assertNull(errorSurface())
    }

    private fun resourceRequest(mainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("file:///android_asset/dashboard/index.html")

            override fun isForMainFrame(): Boolean = mainFrame

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    private fun resourceError(
        code: Int,
        description: String,
    ): WebResourceError = TestWebResourceError(code, description)

    // ---- B2: dashboard-handshake load watchdog ---------------------------------------

    private fun elapseLoadWatchdog() {
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(DashboardRecoveryCoordinator.DASHBOARD_LOAD_TIMEOUT_MS + 500))
    }

    @Test
    fun missedHandshakeShowsTheFailedToLoadSurfaceWithoutKillingTheWebView() {
        val webView = currentWebView()

        elapseLoadWatchdog()

        assertNotNull("the watchdog must surface the failure natively", errorSurface())
        assertEquals(
            activity.getString(R.string.dashboard_failed_to_load),
            activity.findViewById<android.widget.TextView>(R.id.dashboard_error_message).text.toString(),
        )
        assertEquals(
            "the surface must offer Retry",
            View.VISIBLE,
            activity.findViewById<Button>(R.id.dashboard_error_retry).visibility,
        )
        assertEquals("the still-loading WebView is left alone", webView, currentWebView())
    }

    @Test
    fun handshakeBeforeTheTimeoutKeepsTheWatchdogQuiet() {
        activity.onDashboardReady()

        elapseLoadWatchdog()

        assertNull("no false positive after a healthy handshake", errorSurface())
    }

    @Test
    fun lateHandshakeDismissesTheWatchdogSurface() {
        elapseLoadWatchdog()
        assertNotNull(errorSurface())

        activity.onDashboardReady()

        assertNull("a slow cold start self-heals when the handshake finally lands", errorSurface())
        assertTrue(activity.isDashboardReadyForTest())
    }

    @Test
    fun retryAfterTheTimeoutReloadsTheDashboard() {
        val stalled = currentWebView()
        elapseLoadWatchdog()

        activity.findViewById<Button>(R.id.dashboard_error_retry).performClick()

        assertNull("Retry clears the surface", errorSurface())
        val reloaded = currentWebView()
        assertNotSame("Retry rebuilds the WebView", stalled, reloaded)
        assertNotNull(shadowOf(reloaded).getJavascriptInterface("VoltTrackerAndroid"))
        assertEquals(
            "file:///android_asset/dashboard/index.html",
            shadowOf(reloaded).lastLoadedUrl,
        )
    }
}
