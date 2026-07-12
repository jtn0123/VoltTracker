package com.volttracker.obdpoc

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager

/**
 * A real adapter session must hold a partial wake lock (Doze can suspend the CPU mid-drive
 * even under a foreground service); demo sessions must not, and every session end releases.
 */
@RunWith(RobolectricTestRunner::class)
class ObdServiceWakeLockTest {
    private val controllers = mutableListOf<ServiceController<ObdServiceIntegrationTest.TestObdService>>()

    @Before
    fun setUp() {
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        ShadowPowerManager.clearWakeLocks()
    }

    @Test
    fun connectSessionAcquiresPartialWakeLock() {
        dispatch(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF")

        val latest = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("connect session must acquire a wake lock", latest)
        assertTrue("wake lock must be held during the session", latest.isHeld)
    }

    @Test
    fun demoSessionDoesNotAcquireWakeLock() {
        dispatch(ObdService.ACTION_DEMO, null)

        assertNull("demo session must not hold a wake lock", ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun disconnectReleasesWakeLock() {
        val service = dispatch(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF")
        val latest = ShadowPowerManager.getLatestWakeLock()
        assertTrue(latest.isHeld)

        val disconnect =
            Intent(RuntimeEnvironment.getApplication(), ObdServiceIntegrationTest.TestObdService::class.java)
        disconnect.action = ObdService.ACTION_DISCONNECT
        service.onStartCommand(disconnect, 0, 2)

        assertFalse("wake lock must be released on disconnect", latest.isHeld)
    }

    @Test
    fun markSessionInactiveReleasesWakeLock() {
        // B1: one-shot runners (diagnostic scan / clear-DTC / TPMS / cell probe) and pre-connect
        // aborts end their session through markSessionInactive() on the poll thread, never via
        // stopCurrentSession — the wake lock must be released there too, or it leaks until its
        // 12-hour timeout ceiling.
        val service = dispatch(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF")
        val latest = ShadowPowerManager.getLatestWakeLock()
        assertTrue(latest.isHeld)

        service.markSessionInactive()

        assertFalse(
            "wake lock must be released when the runner marks the session inactive",
            latest.isHeld,
        )
    }

    @Test
    fun destroyReleasesWakeLock() {
        val controller = newController(intentFor(ObdService.ACTION_CONNECT, "AA:BB:CC:DD:EE:FF"))
        controller.create().startCommand(0, 1)
        val latest = ShadowPowerManager.getLatestWakeLock()
        assertTrue(latest.isHeld)

        controller.destroy()

        assertFalse("wake lock must be released when the service is destroyed", latest.isHeld)
    }

    private fun dispatch(
        action: String,
        address: String?,
    ): ObdServiceIntegrationTest.TestObdService {
        val controller = newController(intentFor(action, address))
        controller.create().startCommand(0, 1)
        return controller.get()
    }

    private fun newController(intent: Intent): ServiceController<ObdServiceIntegrationTest.TestObdService> {
        val controller = Robolectric.buildService(ObdServiceIntegrationTest.TestObdService::class.java, intent)
        controllers += controller
        return controller
    }

    private fun intentFor(
        action: String,
        address: String?,
    ): Intent {
        val intent = Intent(RuntimeEnvironment.getApplication(), ObdServiceIntegrationTest.TestObdService::class.java)
        intent.action = action
        if (address != null) intent.putExtra(ObdService.EXTRA_ADDRESS, address)
        intent.putExtra(ObdService.EXTRA_NAME, "Test Adapter")
        return intent
    }
}
