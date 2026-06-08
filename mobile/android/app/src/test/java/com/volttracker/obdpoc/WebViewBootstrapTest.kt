package com.volttracker.obdpoc

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewBootstrapTest {
    @Test
    fun configureKeepsDashboardWebViewHardened() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val webView = WebView(activity)

            WebViewBootstrap.configure(webView, VoltBridge(activity))

            val settings = webView.settings
            assertTrue(settings.javaScriptEnabled)
            assertTrue(settings.domStorageEnabled)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertEquals(100, settings.textZoom)
            assertFalse(settings.builtInZoomControls)
            assertFalse(settings.displayZoomControls)
            assertFalse(settings.useWideViewPort)
            assertFalse(settings.loadWithOverviewMode)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun configureAttachesBridgeAndLoadsDashboardWithoutMarkingPageReady() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val webView = WebView(activity)
            val bridge = VoltBridge(activity)

            WebViewBootstrap.configure(webView, bridge)

            val shadowWebView = shadowOf(webView)
            assertSame(bridge, shadowWebView.getJavascriptInterface("VoltTrackerAndroid"))
            assertEquals(
                "file:///android_asset/dashboard/index.html",
                shadowWebView.getLastLoadedUrl(),
            )
            assertFalse(activity.isDashboardReadyForTest())
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Suppress("DEPRECATION") // exercise the String overload that fires on API 23
    fun configureBlocksNavigationOutsideTheDashboardOrigin() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val webView = WebView(activity)

            WebViewBootstrap.configure(webView, VoltBridge(activity))

            val client = shadowOf(webView).getWebViewClient()
            assertNotNull(client)
            // In-app dashboard navigation is allowed (not overridden).
            assertFalse(
                client.shouldOverrideUrlLoading(
                    webView,
                    "file:///android_asset/dashboard/index.html",
                ),
            )
            // Anything off-origin is consumed (blocked) rather than navigated to.
            assertTrue(client.shouldOverrideUrlLoading(webView, "https://example.com/phish"))
            assertTrue(client.shouldOverrideUrlLoading(webView, "file:///etc/passwd"))
        } finally {
            controller.destroy()
        }
    }
}
