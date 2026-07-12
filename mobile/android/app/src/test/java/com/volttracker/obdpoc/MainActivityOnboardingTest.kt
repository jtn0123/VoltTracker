package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.DialogInterface
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

/**
 * The guided first-run setup walkthrough (M7) must auto-show on the first dashboard handshake of a
 * fresh install, adapt its step sequence to the live permission/adapter state (skipping satisfied
 * steps), expose a working demo-skip path, persist completion so it never auto-shows again, and
 * still be re-openable on demand via the bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityOnboardingTest {
    private val controllers = mutableListOf<ActivityController<QuietActivity>>()

    @Before
    fun setUp() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ShadowAlertDialog.reset()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        ShadowAlertDialog.reset()
    }

    private fun launch(): QuietActivity {
        val controller = Robolectric.buildActivity(QuietActivity::class.java).setup()
        controllers += controller
        return controller.get()
    }

    private fun launchCreated(): QuietActivity {
        val controller = Robolectric.buildActivity(QuietActivity::class.java).create()
        controllers += controller
        return controller.get()
    }

    private fun latestDialog() = ShadowAlertDialog.getLatestAlertDialog()

    private fun latestTitle(): String = shadowOf(latestDialog()).title.toString()

    private fun settle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun settlePostReadyWork() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    private fun clickPositive() {
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
    }

    private fun clickSkip() {
        latestDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle()
    }

    private fun grantBluetooth(activity: QuietActivity) {
        shadowOf(activity).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }

    private fun grantLocation(activity: QuietActivity) {
        shadowOf(activity).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun pairAdapter(activity: QuietActivity) {
        // hasPairedAdapter is driven by DeviceCatalog.lastAddress(); remember a valid address.
        activity.requireDeviceCatalog().remember(PAIRED_ADDRESS, "Veepeak OBD")
    }

    @Test
    fun firstDashboardReadyAutoShowsTheGuideStartingAtPairStep() {
        val activity = launch()
        activity.onDashboardReady()
        settlePostReadyWork()

        assertNotNull("fresh install must auto-show the setup guide", latestDialog())
        val title = latestTitle()
        assertTrue("the fresh flow should open on the pair-adapter step: $title", title.contains("Pair your OBD"))
        // Fresh user: pair, bluetooth, location, connect (4 actionable) + completion = 5 presented.
        assertTrue("progress should read 'Step 1 of 5': $title", title.contains("Step 1 of 5"))
    }

    @Test
    fun satisfiedStepsAreSkippedSoTheFlowAdaptsToLiveState() {
        val activity = launch()
        // Adapter already paired and Bluetooth already granted before the first handshake.
        grantBluetooth(activity)
        pairAdapter(activity)

        activity.onDashboardReady()
        settlePostReadyWork()

        val title = latestTitle()
        // Pair + Bluetooth are done/skipped, so the flow opens on location as step 1 of a shorter
        // flow (location, connect + completion = 3 presented).
        assertTrue("the first two steps should be skipped, opening on location: $title", title.contains("location"))
        assertTrue("progress total should shrink to reflect skipped steps: $title", title.contains("Step 1 of 3"))
    }

    @Test
    fun fullyReadyUserGoesStraightToCompletion() {
        val activity = launch()
        grantBluetooth(activity)
        grantLocation(activity)
        pairAdapter(activity)

        // Every prerequisite is satisfied, so it must not auto-show.
        activity.onDashboardReady()
        settlePostReadyWork()
        assertNull("a fully set-up user has nothing to guide; no auto-show", latestDialog())

        // But re-opening on demand still surfaces the flow — the only remaining step is the
        // connect/demo call-to-action, which then leads to the completion screen.
        activity.diagnostics().openSetupGuideFromBridge()
        assertTrue("re-open should surface the connect/demo step: ${latestTitle()}", latestTitle().contains("demo"))
        clickSkip()
        assertTrue("skipping the CTA reaches completion: ${latestTitle()}", latestTitle().contains("all set"))
    }

    @Test
    fun completingTheGuideSuppressesAnyFurtherAutoShow() {
        val activity = launch()
        activity.onDashboardReady()
        settlePostReadyWork()
        assertNotNull("fresh install must auto-show the setup guide", latestDialog())

        // Skip through every actionable step to the completion screen, then tap Done.
        repeat(SKIP_STEPS) { clickSkip() }
        assertTrue("skipping should reach the completion screen: ${latestTitle()}", latestTitle().contains("all set"))
        clickPositive()

        // Relaunch: the persisted completion marker suppresses any further onboarding.
        ShadowAlertDialog.reset()
        val second = launch()
        second.onDashboardReady()
        settlePostReadyWork()
        assertNull("the guide must not auto-show again after completion", latestDialog())
    }

    @Test
    fun connectStepDemoButtonStartsDemoSession() {
        val activity = launchCreated()
        // Paired + Bluetooth granted, location withheld: the next actionable step after location is
        // connect/demo. Walk: location (skip) -> connect/demo.
        grantBluetooth(activity)
        pairAdapter(activity)
        activity.diagnostics().openSetupGuideFromBridge()

        // First presented step is location; skip it to reach the connect/demo step.
        assertTrue("re-open should open on location: ${latestTitle()}", latestTitle().contains("location"))
        clickSkip()

        assertTrue(
            "after skipping location, the connect/demo step shows: ${latestTitle()}",
            latestTitle().contains("demo"),
        )
        // Tapping "Try the demo" starts the synthetic demo session and finishes the guide.
        clickPositive()

        val started = shadowOf(activity).nextStartedService
        assertNotNull("the demo button should start a service", started)
        assertTrue(
            "the demo button must start the demo session (no Bluetooth needed): ${started?.action}",
            started?.action == ObdService.ACTION_DEMO,
        )
    }

    @Test
    fun reopeningTheGuideWorksEvenAfterCompletion() {
        val activity = launch()
        // Mark completed by walking the fresh flow to the end.
        activity.onDashboardReady()
        settlePostReadyWork()
        repeat(SKIP_STEPS) { clickSkip() }
        clickPositive()
        ShadowAlertDialog.reset()

        // Auto-show stays suppressed, but the bridge re-open still surfaces the guide.
        activity.diagnostics().openSetupGuideFromBridge()
        assertNotNull("the Setup guide affordance must re-open the walkthrough after completion", latestDialog())
        assertTrue(
            "re-open on a fresh-perms device starts at pairing: ${latestTitle()}",
            latestTitle().contains("Pair your OBD"),
        )
    }

    /** Publish seams neutralized so the handshake under test stays synchronous and quiet. */
    open class QuietActivity : MainActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            // Bluetooth must report enabled so isBluetoothReady()/connect paths behave; the demo
            // path itself does not need it, but other publish calls read it.
            val manager = getSystemService(BluetoothManager::class.java)
            manager?.adapter?.let { shadowOf(it).setEnabled(true) }
        }

        override fun publishDeviceList() {}

        override fun publishStorageSummary() {}

        override fun markStorageSummaryDirty() {}

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {}

        override fun onAdapterStatusForReadyNotify(status: JSONObject?) {}
    }

    private companion object {
        const val PAIRED_ADDRESS = "AA:BB:CC:DD:EE:FF"

        /** Actionable steps in the fresh flow (pair, bluetooth, location, connect) to skip past. */
        const val SKIP_STEPS = 4
    }
}
