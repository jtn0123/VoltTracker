package com.volttracker.obdpoc

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * A1 — pins every [DiagnosticsHostDelegate] forward onto the matching [TroubleshooterBridge]
 * command (and the setup-guide lambda), since the flat MainActivity overrides these bodies replaced
 * are no longer on any test's path: the bridge dispatch tests substitute the whole cluster through
 * the `diagnostics()` accessor. Each test drives the REAL TroubleshooterBridge down a cheap,
 * deterministic branch (the defensive early returns TroubleshooterBridgeTest already relies on) so
 * a renamed or crossed-up forward fails here instead of on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsHostDelegateTest {
    private var controller: ActivityController<HarnessActivity>? = null
    private var bridge: TroubleshooterBridge? = null
    private var delegate: DiagnosticsHostDelegate? = null
    private var troubleshooterReads = 0
    private var openSetupGuideCalls = 0

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        this.controller = controller
        val activity = controller.get()
        // Guarantee the "nothing to share" branch for the zip share below.
        wipe(File(activity.filesDir, "obd-logs"))
        wipe(File(activity.filesDir, "app-log"))
        troubleshooterReads = 0
        openSetupGuideCalls = 0
        val bridge = TroubleshooterBridge(activity)
        this.bridge = bridge
        delegate =
            DiagnosticsHostDelegate(
                troubleshooter = {
                    troubleshooterReads += 1
                    bridge
                },
                openSetupGuide = { openSetupGuideCalls += 1 },
            )
    }

    @After
    fun tearDown() {
        bridge?.shutdown()
        controller?.destroy()
    }

    @Test
    fun forceStopPackageForwardsToTroubleshooter() {
        // Null/blank packages take the bridge's validation early-return; reaching it at all
        // proves the delegate handed the call to TroubleshooterBridge.forceStopPackage.
        assertFalse(delegate!!.forceStopPackageFromBridge(null))
        assertFalse(delegate!!.forceStopPackageFromBridge(""))
        assertEquals(2, troubleshooterReads)
    }

    @Test
    fun getRecentSessionsForwardsToTroubleshooter() {
        // n <= 0 short-circuits inside the bridge with the exact "[]" contract shape.
        assertEquals("[]", delegate!!.getRecentSessionsJson(0))
        assertEquals(1, troubleshooterReads)
    }

    @Test
    fun cancelRetryForwardsToTroubleshooter() {
        delegate!!.cancelRetryFromBridge()

        assertEquals(1, troubleshooterReads)
        assertEquals("the cancel-retry intent must reach the service", 1, controller!!.get().startServiceCalls)
    }

    @Test
    fun openBluetoothSettingsForwardsToTroubleshooter() {
        delegate!!.openBluetoothSettingsFromBridge()

        assertEquals(1, troubleshooterReads)
        assertNotNull("the Bluetooth settings intent must launch", controller!!.get().lastStartedIntent)
    }

    @Test
    fun shareDiagnosticsForwardsToTroubleshooter() {
        // With no session logs on disk the bridge publishes a status instead of sharing;
        // the delegate's job is only to get the call there.
        delegate!!.shareDiagnosticsFromBridge()

        assertEquals(1, troubleshooterReads)
    }

    @Test
    fun shareDiagnosticsDigestForwardsToTroubleshooter() {
        // Deny the cache dir so the bridge takes its deterministic build-failure branch
        // (avoids the FileProvider plumbing the TroubleshooterBridge tests own).
        val activity = controller!!.get()
        activity.cacheDirFailure = IllegalStateException("cache unavailable")
        try {
            delegate!!.shareDiagnosticsDigestFromBridge()
        } finally {
            activity.cacheDirFailure = null
        }

        assertEquals(1, troubleshooterReads)
        assertEquals("blocked", activity.lastState)
    }

    @Test
    fun startTestConnectionForwardsToTroubleshooter() {
        // No remembered adapter in the fresh catalog: the bridge publishes a status rather
        // than starting a probe, which is exactly the cheap branch we want to traverse.
        delegate!!.startTestConnectionFromBridge()

        assertEquals(1, troubleshooterReads)
    }

    @Test
    fun adapterReadyNotifySchedulePairForwardsToTroubleshooter() {
        delegate!!.scheduleAdapterReadyNotifyFromBridge(1)
        delegate!!.cancelAdapterReadyNotifyFromBridge()

        assertEquals("both schedule and cancel must re-read the troubleshooter", 2, troubleshooterReads)
    }

    @Test
    fun openSetupGuideInvokesTheGuideLambdaWithoutTouchingTheTroubleshooter() {
        delegate!!.openSetupGuideFromBridge()

        assertEquals(1, openSetupGuideCalls)
        assertEquals("the setup guide is not a troubleshooter command", 0, troubleshooterReads)
    }

    /**
     * Same skip-super seam as [TroubleshooterBridgeTest.HarnessActivity]: only the collaborators
     * the bridge's early-return branches touch are wired (a scratch [DeviceCatalog], recorded
     * status/intent/service dispatch), so no WebView or store spins up.
     */
    class HarnessActivity : MainActivity() {
        var lastState: String? = null
        var lastStartedIntent: Intent? = null
        var startServiceCalls = 0
        var cacheDirFailure: RuntimeException? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            deviceCatalog = DeviceCatalog(this, getSharedPreferences("diagnostics-delegate-test", 0))
        }

        override fun getCacheDir(): File {
            cacheDirFailure?.let { throw it }
            return super.getCacheDir()
        }

        override fun startActivity(intent: Intent?) {
            lastStartedIntent = intent
        }

        override fun startService(service: Intent?): ComponentName? {
            startServiceCalls += 1
            return super.startService(service)
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
        }
    }

    private companion object {
        fun wipe(dir: File) {
            if (!dir.exists()) {
                return
            }
            assertTrue("could not clear $dir", dir.deleteRecursively())
        }
    }
}
