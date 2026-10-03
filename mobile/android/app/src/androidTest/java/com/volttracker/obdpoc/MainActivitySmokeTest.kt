package com.volttracker.obdpoc

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke: MainActivity must come up and the WebView dashboard must complete the
 * JS->native handshake ([MainActivity.onDashboardReady] flips the publisher to page-ready).
 * This is the instrumented twin of scripts/emulator-smoke.sh's logcat check — it proves the
 * real WebView + bridge + asset bundle work on an actual Android runtime, which Robolectric
 * and jsdom cannot.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun dashboardHandshakeCompletesAfterLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready =
                scenario.awaitOnUiThread(HANDSHAKE_TIMEOUT_MS) { activity, done ->
                    if (activity.isDashboardReadyForTest()) done.set(true)
                }
            assertTrue("dashboard JS handshake did not complete within ${HANDSHAKE_TIMEOUT_MS}ms", ready)
        }
    }

    private companion object {
        // Cold WebView + esbuild bundle parse on an emulator; generous to avoid CI flake.
        const val HANDSHAKE_TIMEOUT_MS = 45_000L
    }
}
