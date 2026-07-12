package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.service.ObdService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device smoke for the full native->WebView telemetry pipeline, one step past
 * [MainActivitySmokeTest] (handshake only) and [ObdServiceDemoSmokeTest] (broadcasts only):
 * after the dashboard JS handshake completes, a demo session ([ObdService.ACTION_DEMO], no
 * Bluetooth hardware needed) must produce telemetry that the Activity's broadcast coordinator
 * forwards through [DashboardPublisher] into the real WebView, where the dashboard's
 * `updateTelemetry` entry point stamps `VoltDashboard.state.lastSampleAt` and stores the
 * sample. That last hop — WebView `evaluateJavascript` into the bundled dashboard JS — is
 * exactly what Robolectric and the jsdom suite cannot exercise.
 */
@RunWith(AndroidJUnit4::class)
class DashboardDemoTelemetrySmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // End the demo session so a following test starts from an idle service.
        val stop = Intent(context, ObdService::class.java)
        stop.action = ObdService.ACTION_DISCONNECT
        runCatching { context.startService(stop) }
    }

    @Test
    fun demoTelemetryReachesDashboardAfterHandshake() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboardHandshake(scenario)

            // Start the synthetic demo polling loop — the same intent the in-app "Demo" entry
            // points send. It runs with no adapter/socket, so this works on a bare emulator.
            val demo = Intent(context, ObdService::class.java)
            demo.action = ObdService.ACTION_DEMO
            ContextCompat.startForegroundService(context, demo)

            awaitDashboardTelemetry(scenario)
        }
    }

    /** Polls [MainActivity.isDashboardReadyForTest] like [MainActivitySmokeTest] does. */
    private fun awaitDashboardHandshake(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity -> ready = activity.isDashboardReadyForTest() }
            if (ready) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("dashboard JS handshake did not complete within ${HANDSHAKE_TIMEOUT_MS}ms")
    }

    /**
     * Polls the live dashboard page for evidence that a telemetry sample arrived through the
     * bridge. `evaluateJavascript` must run on the UI thread ([ActivityScenario.onActivity])
     * and reports asynchronously, so the result latch is checked on the next poll pass.
     */
    private fun awaitDashboardTelemetry(scenario: ActivityScenario<MainActivity>) {
        val telemetrySeen = AtomicBoolean(false)
        val deadline = System.currentTimeMillis() + TELEMETRY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (telemetrySeen.get()) {
                return
            }
            scenario.onActivity { activity ->
                activity.webViewForTest()?.evaluateJavascript(TELEMETRY_PROBE_JS) { result ->
                    if (result == "\"yes\"") {
                        telemetrySeen.set(true)
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        if (!telemetrySeen.get()) {
            fail("no demo telemetry reached the dashboard bridge within ${TELEMETRY_TIMEOUT_MS}ms")
        }
    }

    private companion object {
        // Cold WebView + esbuild bundle parse on an emulator; generous to avoid CI flake.
        const val HANDSHAKE_TIMEOUT_MS = 45_000L

        // The demo loop streams about one sample per second; generous to avoid CI flake.
        const val TELEMETRY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 250L

        // telemetry.ts's updateTelemetry sets state.lastSampleAt (wall clock, > 0) for every
        // sample that arrives via window.VoltTrackerNative — the observable "a sample made it
        // all the way into the page" signal. sampleCount is the demo loop's own counter,
        // proving the sample carries real payload fields, not just a truthy timestamp.
        val TELEMETRY_PROBE_JS =
            """
            (function () {
              try {
                var vd = window.VoltDashboard;
                if (!vd || !vd.state) return 'no-dashboard';
                var sampleAt = Number(vd.state.lastSampleAt);
                var sampleCount = Number(vd.state.telemetry && vd.state.telemetry.sampleCount);
                return sampleAt > 0 && sampleCount > 0 ? 'yes' : 'no';
              } catch (err) {
                return 'error';
              }
            })()
            """.trimIndent()
    }
}
