package com.volttracker.obdpoc

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

/**
 * The publish path's security/lifecycle gating, exercised without an Activity — the point of
 * extracting [DashboardPublisher] out of MainActivity (A4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardPublisherTest {
    private fun publisherFor(
        webView: WebView,
        alive: Boolean,
    ): DashboardPublisher {
        val publisher = DashboardPublisher(webView, { alive }, Runnable::run)
        publisher.setPageReady(true)
        return publisher
    }

    @Test
    fun allowlistedFunctionIsInvokedWithQuotedPayload() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val shadow: ShadowWebView = shadowOf(webView)
        val publisher = publisherFor(webView, true)

        publisher.publish("setStatus", "{\"state\":\"ready\"}")

        val script = shadow.getLastEvaluatedJavascript()!!
        assertTrue(
            "expected the whitelisted call",
            script.contains("window.VoltTrackerNative.setStatus("),
        )
        // Payload is JSON-quoted, so it lands as a single string argument.
        assertTrue(script.contains("\\\"state\\\""))
    }

    @Test
    fun restoreProgressFunctionIsAllowlisted() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val shadow: ShadowWebView = shadowOf(webView)
        val publisher = publisherFor(webView, true)

        publisher.publish("setRestoreProgress", "{\"visible\":true,\"busy\":true}")

        val script = shadow.getLastEvaluatedJavascript()!!
        assertTrue(
            "expected the restore progress callback",
            script.contains("window.VoltTrackerNative.setRestoreProgress("),
        )
        assertTrue(script.contains("\\\"visible\\\""))
    }

    @Test
    fun asyncStorageCallbackFunctionsAreAllowlisted() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val shadow: ShadowWebView = shadowOf(webView)
        val publisher = publisherFor(webView, true)

        val callbacks =
            listOf(
                "setTrips",
                "setInsights",
                "setTripRoute",
                "setCurrentSessionRoute",
                "setBatterySohHistory",
                "showTripUndo",
                "setBackupReceipt",
                "openTripReceipt",
                "restoreView",
            )
        for (functionName in callbacks) {
            publisher.publish(functionName, "{\"ok\":true}")
            assertTrue(
                "expected $functionName callback",
                shadow.getLastEvaluatedJavascript()!!.contains("window.VoltTrackerNative.$functionName("),
            )
        }
    }

    @Test
    fun lineSeparatorsInPayloadAreEscapedNotRaw() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val shadow: ShadowWebView = shadowOf(webView)
        val publisher = publisherFor(webView, true)

        // U+2028 (line separator) and U+2029 (paragraph separator) are JS line terminators inside a
        // string literal on pre-ES2019 WebViews; a raw one makes evaluateJavascript throw SyntaxError
        // and silently drop the update. JSONObject.quote leaves them raw, so the publisher must escape.
        publisher.publish("setStatus", "{\"detail\":\"a\u2028b\u2029c\"}")

        val script = shadow.getLastEvaluatedJavascript()!!
        assertTrue("U+2028 must be escaped to its \\u2028 form", script.contains("\\u2028"))
        assertTrue("U+2029 must be escaped to its \\u2029 form", script.contains("\\u2029"))
        assertFalse("no raw line separator may survive into the evaluated script", script.contains("\u2028"))
        assertFalse("no raw paragraph separator may survive into the evaluated script", script.contains("\u2029"))
    }

    @Test
    fun unknownFunctionIsRefused() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val publisher = publisherFor(webView, true)

        publisher.publish("evilFunction", "{}")

        assertNull(
            "off-allowlist call must never reach the WebView",
            shadowOf(webView).getLastEvaluatedJavascript(),
        )
    }

    @Test
    fun publishBeforePageReadyIsDropped() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val publisher = DashboardPublisher(webView, { true }, Runnable::run)
        // pageReady left false.

        publisher.publish("setStatus", "{}")

        assertNull(shadowOf(webView).getLastEvaluatedJavascript())
    }

    @Test
    fun publishAfterTearDownIsDropped() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val publisher = publisherFor(webView, false) // host finishing/destroyed

        publisher.publish("setStatus", "{}")

        assertNull(shadowOf(webView).getLastEvaluatedJavascript())
    }

    @Test
    fun pageReadyFlagRoundTrips() {
        val publisher =
            DashboardPublisher(
                WebView(RuntimeEnvironment.getApplication()),
                { true },
                Runnable::run,
            )
        assertEquals(false, publisher.isPageReady())
        publisher.setPageReady(true)
        assertEquals(true, publisher.isPageReady())
    }
}
