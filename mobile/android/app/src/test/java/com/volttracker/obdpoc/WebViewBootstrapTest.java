package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.webkit.WebSettings;
import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WebViewBootstrapTest {

    @Test
    public void configureKeepsDashboardWebViewHardened() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = controller.get();
            WebView webView = new WebView(activity);

            WebViewBootstrap.configure(webView, new VoltBridge(activity));

            WebSettings settings = webView.getSettings();
            assertTrue(settings.getJavaScriptEnabled());
            assertTrue(settings.getDomStorageEnabled());
            assertFalse(settings.getAllowFileAccess());
            assertFalse(settings.getAllowContentAccess());
            assertEquals(100, settings.getTextZoom());
            assertFalse(settings.getBuiltInZoomControls());
            assertFalse(settings.getDisplayZoomControls());
            assertFalse(settings.getUseWideViewPort());
            assertFalse(settings.getLoadWithOverviewMode());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void configureAttachesBridgeAndLoadsDashboardWithoutMarkingPageReady() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = controller.get();
            WebView webView = new WebView(activity);
            VoltBridge bridge = new VoltBridge(activity);

            WebViewBootstrap.configure(webView, bridge);

            ShadowWebView shadowWebView = shadowOf(webView);
            assertSame(bridge, shadowWebView.getJavascriptInterface("VoltTrackerAndroid"));
            assertEquals(
                    "file:///android_asset/dashboard/index.html", shadowWebView.getLastLoadedUrl());
            assertFalse(activity.isDashboardReadyForTest());
        } finally {
            controller.destroy();
        }
    }
}
