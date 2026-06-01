package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

/**
 * The publish path's security/lifecycle gating, exercised without an Activity — the point of
 * extracting {@link DashboardPublisher} out of MainActivity (A4).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DashboardPublisherTest {

    private DashboardPublisher publisherFor(WebView webView, boolean alive) {
        DashboardPublisher publisher = new DashboardPublisher(webView, () -> alive, Runnable::run);
        publisher.setPageReady(true);
        return publisher;
    }

    @Test
    public void allowlistedFunctionIsInvokedWithQuotedPayload() {
        WebView webView = new WebView(RuntimeEnvironment.getApplication());
        ShadowWebView shadow = shadowOf(webView);
        DashboardPublisher publisher = publisherFor(webView, true);

        publisher.publish("setStatus", "{\"state\":\"ready\"}");

        String script = shadow.getLastEvaluatedJavascript();
        assertTrue(
                "expected the whitelisted call",
                script.contains("window.VoltTrackerNative.setStatus("));
        // Payload is JSON-quoted, so it lands as a single string argument.
        assertTrue(script.contains("\\\"state\\\""));
    }

    @Test
    public void unknownFunctionIsRefused() {
        WebView webView = new WebView(RuntimeEnvironment.getApplication());
        DashboardPublisher publisher = publisherFor(webView, true);

        publisher.publish("evilFunction", "{}");

        assertNull(
                "off-allowlist call must never reach the WebView",
                shadowOf(webView).getLastEvaluatedJavascript());
    }

    @Test
    public void publishBeforePageReadyIsDropped() {
        WebView webView = new WebView(RuntimeEnvironment.getApplication());
        DashboardPublisher publisher = new DashboardPublisher(webView, () -> true, Runnable::run);
        // pageReady left false.

        publisher.publish("setStatus", "{}");

        assertNull(shadowOf(webView).getLastEvaluatedJavascript());
    }

    @Test
    public void publishAfterTearDownIsDropped() {
        WebView webView = new WebView(RuntimeEnvironment.getApplication());
        DashboardPublisher publisher = publisherFor(webView, false); // host finishing/destroyed

        publisher.publish("setStatus", "{}");

        assertNull(shadowOf(webView).getLastEvaluatedJavascript());
    }

    @Test
    public void pageReadyFlagRoundTrips() {
        DashboardPublisher publisher =
                new DashboardPublisher(
                        new WebView(RuntimeEnvironment.getApplication()),
                        () -> true,
                        Runnable::run);
        assertEquals(false, publisher.isPageReady());
        publisher.setPageReady(true);
        assertEquals(true, publisher.isPageReady());
    }
}
