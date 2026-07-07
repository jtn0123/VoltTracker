package com.volttracker.obdpoc

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
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
            // Deprecated settings are still honored by the WebView, so the hardened profile pins
            // them off and this test reads them back through the only (deprecated) getters.
            @Suppress("DEPRECATION")
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowFileAccessFromFileURLs)
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

    @Test
    fun configuredChromeClientConsumesDashboardConsoleMessages() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val webView = WebView(activity)

            WebViewBootstrap.configure(webView, VoltBridge(activity))

            val chromeClient = shadowOf(webView).getWebChromeClient()
            assertNotNull(chromeClient)
            assertTrue(chromeClient.onConsoleMessage(null))
            assertTrue(
                chromeClient.onConsoleMessage(
                    ConsoleMessage(
                        "boom",
                        "dashboard.js",
                        12,
                        ConsoleMessage.MessageLevel.ERROR,
                    ),
                ),
            )
            assertTrue(
                chromeClient.onConsoleMessage(
                    ConsoleMessage(
                        "careful",
                        "dashboard.js",
                        13,
                        ConsoleMessage.MessageLevel.WARNING,
                    ),
                ),
            )
            assertTrue(
                chromeClient.onConsoleMessage(
                    ConsoleMessage(
                        "debug detail",
                        "dashboard.js",
                        14,
                        ConsoleMessage.MessageLevel.DEBUG,
                    ),
                ),
            )
            assertTrue(
                chromeClient.onConsoleMessage(
                    ConsoleMessage(
                        "plain log",
                        "dashboard.js",
                        15,
                        ConsoleMessage.MessageLevel.LOG,
                    ),
                ),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun configuredWebViewClientHandlesLifecycleCallbacksAndRequestNavigationGuard() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val webView = WebView(activity)

            WebViewBootstrap.configure(webView, VoltBridge(activity))

            val client = shadowOf(webView).getWebViewClient()
            assertNotNull(client)
            client.onPageStarted(webView, "file:///android_asset/dashboard/index.html", null as Bitmap?)
            client.onPageCommitVisible(webView, "file:///android_asset/dashboard/index.html")
            client.onPageFinished(webView, "file:///android_asset/dashboard/index.html")

            assertFalse(
                client.shouldOverrideUrlLoading(
                    webView,
                    request("file:///android_asset/dashboard/settings.html"),
                ),
            )
            assertTrue(
                client.shouldOverrideUrlLoading(
                    webView,
                    request("https://example.com/off-origin"),
                ),
            )
            assertTrue(client.shouldOverrideUrlLoading(webView, null as WebResourceRequest?))
        } finally {
            controller.destroy()
        }
    }

    private fun request(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
}
