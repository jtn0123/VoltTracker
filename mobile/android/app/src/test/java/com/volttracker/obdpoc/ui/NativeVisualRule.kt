package com.volttracker.obdpoc.ui

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone

/**
 * The pinned environment of the reviewed native baselines (see `baselines/README.md`). Pair it with
 * `@GraphicsMode(NATIVE)` and `@Config(sdk = [SDK], qualifiers = QUALIFIERS)`. Each test runs in
 * [zone] (UTC unless a matrix's sample data needs its own fixed offset) with 12-hour time; [capture]
 * turns animations off, composes the real [VoltApp], waits for its nav bar, freezes the frame clock
 * one second in and captures `baselines/<name>.png`. Plain unit runs only compose it;
 * `verifyNativeVisual` compares it and `-PnativeVisualRecord` rewrites it.
 */
class NativeVisualRule(
    private val zone: TimeZone = TimeZone.getTimeZone("UTC"),
) : TestRule {
    val compose: ComposeContentTestRule = createComposeRule()

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        compose.apply(
            object : Statement() {
                override fun evaluate() {
                    val originalZone = TimeZone.getDefault()
                    TimeZone.setDefault(zone)
                    Settings.System.putString(resolver(), Settings.System.TIME_12_24, "12")
                    try {
                        base.evaluate()
                    } finally {
                        TimeZone.setDefault(originalZone)
                    }
                }
            },
            description,
        )

    fun capture(
        name: String,
        state: VoltAppUiState,
        tab: VoltTab = VoltTab.DRIVE,
        routes: List<VoltRoute> = emptyList(),
        /** A taller window than [QUALIFIERS] to capture a whole page, e.g. one whose key state scrolls. */
        heightDp: Int? = null,
    ) {
        heightDp?.let { RuntimeEnvironment.setQualifiers("+h${it}dp") }
        // No pulsing/infinite animations or wall-clock-dependent live data in the baseline.
        Settings.Global.putFloat(resolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { VoltApp(state, initialTab = tab, initialRoutes = routes) }
        compose.onNodeWithContentDescription("Trips").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(FROZEN_FRAME_MS)
        compose.onRoot().captureRoboImage("$BASELINES/$name.png")
    }

    private fun resolver() = RuntimeEnvironment.getApplication().contentResolver

    companion object {
        /** The Android SDK the baselines render on. */
        const val SDK = 34

        /** English/US, 412 × 915 dp at 420 dpi: a typical tall phone. */
        const val QUALIFIERS = "en-rUS-w412dp-h915dp-420dpi"

        private const val BASELINES = "src/test/java/com/volttracker/obdpoc/ui/baselines"
        private const val FROZEN_FRAME_MS = 1_000L
    }
}
