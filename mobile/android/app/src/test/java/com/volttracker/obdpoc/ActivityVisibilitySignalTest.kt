package com.volttracker.obdpoc

import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.service.AppVisibility
import com.volttracker.obdpoc.service.ObdService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Both dashboards report resume/pause through [AppVisibility] — never a `startService` — so a share
 * sheet or picker covering the app cannot make ActivityThread wait on pending SharedPreferences
 * writes (`QueuedWork.waitToFinish` runs before every service start command) and ANR.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityVisibilitySignalTest {
    @Before
    fun setUp() {
        // Same native-SQLite warm-up as the other Activity tests (see MainActivityWebViewLifecycleTest).
        ObdLocalStore(RuntimeEnvironment.getApplication()).apply {
            getRecentSessions(1)
            close()
        }
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        AppVisibility.resetForTest()
    }

    @After
    fun tearDown() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        AppVisibility.resetForTest()
    }

    @Test
    fun composeDashboardReportsPauseAndResumeInProcess() {
        val controller = Robolectric.buildActivity(ComposeDashboardActivity::class.java).setup()
        assertTrue(AppVisibility.isForeground)
        drainStartedServices()

        controller.pause()
        assertFalse("pausing (e.g. behind the share sheet) reports background", AppVisibility.isForeground)
        assertNoObdServiceStarted()

        controller.resume()
        assertTrue(AppVisibility.isForeground)
        controller.pause().stop().destroy()
    }

    @Test
    fun classicDashboardReportsPauseAndResumeInProcess() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(AppVisibility.isForeground)
        drainStartedServices()

        controller.pause()
        assertFalse(AppVisibility.isForeground)
        assertNoObdServiceStarted()

        controller.resume()
        assertTrue(AppVisibility.isForeground)
        controller.pause().stop().destroy()
    }

    private fun drainStartedServices(): List<Intent> {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        return generateSequence { app.nextStartedService }.toList()
    }

    private fun assertNoObdServiceStarted() {
        val started = drainStartedServices()
        assertTrue(
            "resume/pause must not send the service a start command: $started",
            started.none { it.component?.className == ObdService::class.java.name },
        )
    }
}
