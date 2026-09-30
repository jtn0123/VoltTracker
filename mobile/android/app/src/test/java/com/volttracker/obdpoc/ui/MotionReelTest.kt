package com.volttracker.obdpoc.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.PageMotion
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Plays the app's animations on a manual clock: screen changes, the live ring gliding between
 * polls, connect → charge, a Health scan, and history loading. Every reel asserts where the
 * motion starts, that both ends are on screen while it runs, and where it lands — so a transition
 * that stops animating (or never finishes) fails here. `-ProborazziRecord` also writes each frame
 * to build/outputs/motion/<reel>/NNNN.png, which `ffmpeg` turns into a clip for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MotionReelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recording = System.getProperty("roborazzi.test.record") == "true"
    private var frame = 0

    private val demoState =
        VoltAppUiState(
            drive = DriveUiState.demo,
            charge = ChargeUiState.demo,
            trips = TripsUiState.demo,
            insights = InsightsUiState.demo,
            car = CarUiState.demo,
            diag = DiagUiState.demo,
            // Filmed in the default dark theme.
            settings = ThemeCase.OLED.applyTo(SettingsUiState.demo),
        )

    /** Runs the clock for [ms], a frame at a time, filming each frame when recording. */
    private fun play(
        reel: String,
        ms: Int,
    ) {
        var elapsed = 0
        while (elapsed < ms) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            // Lets the frame's posted work (clicks, recomposition) run; the clock stays manual.
            compose.waitForIdle()
            elapsed += FRAME_MS.toInt()
            if (recording) {
                val name = String.format(Locale.US, "%04d", frame++)
                compose.onRoot().captureRoboImage("build/outputs/motion/$reel/$name.png")
            }
        }
    }

    private fun start(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun tab(label: String) = compose.onNodeWithContentDescription(label, substring = true)

    @Test
    fun tabsFadeThrough() {
        start { VoltApp(demoState) }
        play("tabs", HOLD_MS)
        listOf("Trips", "Charge", "Insights", "Car", "Drive").forEach {
            tab(it).performClick()
            play("tabs", TAB_MS)
        }
        compose.onNodeWithContentDescription("47 miles per hour", substring = true).assertIsDisplayed()
    }

    @Test
    fun screensSlideInAndBackOut() {
        start { VoltApp(demoState, initialTab = VoltTab.CAR) }
        play("push", HOLD_MS)

        compose.onNodeWithText("Vehicle health").performClick()
        play("push", MID_MS)
        // Mid-slide: the Car tab is still underneath the Health screen sliding over it.
        compose.onNodeWithText("Vehicle health").assertExists()
        compose.onNodeWithText("Health").assertExists()
        play("push", SCREEN_MS)
        compose.onNodeWithText("Vehicle health").assertDoesNotExist()

        compose.onNodeWithText("Freeze frame").performClick()
        play("push", MID_MS + SCREEN_MS)
        compose.onNodeWithText("When it was set", ignoreCase = true).assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        play("push", MID_MS + SCREEN_MS)
        compose.onNodeWithText("Health").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        play("push", MID_MS + SCREEN_MS)
        compose.onNodeWithText("Vehicle health").assertIsDisplayed()
        compose.onNodeWithText("Health").assertDoesNotExist()
    }

    @Test
    fun settingsPagesSlide() {
        start { VoltApp(demoState, initialRoutes = listOf(VoltRoute.SETTINGS)) }
        play("settings", HOLD_MS)
        compose.onNodeWithText("Alerts").performClick()
        play("settings", MID_MS + SCREEN_MS)
        compose.onNodeWithText("Charging complete").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        play("settings", MID_MS + SCREEN_MS)
        compose.onNodeWithText("PREFERENCES").assertIsDisplayed()
    }

    @Test
    fun theRingGlidesBetweenPolls() {
        var drive by mutableStateOf(DriveUiState.demo)
        start { VoltApp(demoState.copy(drive = drive)) }
        play("glide", HOLD_MS)
        // One poll a second, as the car reports: pulling away, then lifting off into regen.
        listOf(58 to 41.0, 66 to 62.5, 61 to 12.0, 49 to -24.0, 38 to -31.5, 35 to 6.0).forEach { (mph, kw) ->
            drive = drive.copy(speedMph = mph, powerKw = kw)
            play("glide", POLL_MS)
        }
        play("glide", HOLD_MS)
        compose.onNodeWithContentDescription("35 miles per hour", substring = true).assertIsDisplayed()
    }

    @Test
    fun connectingParkingAndCharging() {
        var drive by mutableStateOf(DriveUiState())
        start { VoltApp(demoState.copy(drive = drive)) }
        play("phases", HOLD_MS)
        listOf(DriveUiState.demoParked, DriveUiState.demo, DriveUiState.demoGas, DriveUiState.demoCharging).forEach {
            drive = it
            play("phases", PHASE_MS)
        }
        compose.onNodeWithText("Connected · charging").assertIsDisplayed()
    }

    @Test
    fun aHealthScanRunsAndLands() {
        var diag by mutableStateOf(DiagUiState(connected = true))
        start { VoltApp(demoState.copy(diag = diag), initialRoutes = listOf(VoltRoute.HEALTH)) }
        play("scan", HOLD_MS)
        diag = diag.copy(busyLabel = "Scanning…")
        play("scan", SCAN_MS)
        compose.onAllNodesWithText("Scanning…").onFirst().assertIsDisplayed()
        diag = DiagUiState.demo
        play("scan", PHASE_MS)
        compose.onNodeWithText("Scan again").assertIsDisplayed()
    }

    @Test
    fun drivesFadeInOnceTheyAreRead() {
        var trips by mutableStateOf(TripsUiState(history = HistoryLoad.LOADING))
        start { VoltApp(demoState.copy(trips = trips), initialTab = VoltTab.TRIPS) }
        compose.onNodeWithContentDescription("Loading drives…").assertIsDisplayed()
        play("loading", SCAN_MS)
        trips = TripsUiState.demo
        play("loading", PHASE_MS)
        compose.onNodeWithText("ELECTRIC").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading drives…").assertDoesNotExist()
    }

    @Test
    fun pullingDownRefreshesTrips() {
        var refreshes = 0
        start {
            VoltApp(demoState, initialTab = VoltTab.TRIPS, actions = VoltAppActions(onRefresh = { refreshes++ }))
        }
        play("pull", HOLD_MS)
        compose.onRoot().performTouchInput { down(Offset(width / 2f, height * PULL_FROM)) }
        repeat(PULL_STEPS) {
            compose.onRoot().performTouchInput { moveBy(Offset(0f, height * PULL_STEP)) }
            play("pull", FRAME_MS.toInt())
        }
        compose.onRoot().performTouchInput { up() }
        play("pull", PHASE_MS)
        assertEquals(1, refreshes)
        // The spinner has gone again and the drives are still there.
        compose.onNodeWithText("ELECTRIC").assertIsDisplayed()
    }

    @Test
    fun removeAnimationsSkipsTheSlide() {
        val context = compose.activity
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        start { VoltApp(demoState, initialTab = VoltTab.CAR) }

        compose.onNodeWithText("Vehicle health").performClick()
        play("still", MID_MS)
        // No slide to be part-way through: Health is simply there and the Car tab is gone.
        compose.onNodeWithText("Health").assertIsDisplayed()
        compose.onNodeWithText("Vehicle health").assertDoesNotExist()
    }

    @Test
    fun pageMotionFollowsTheStackDepth() {
        val car = VoltPage(VoltTab.CAR, null, 0)
        val health = VoltPage(VoltTab.CAR, VoltRoute.HEALTH, 1)
        val signals = VoltPage(VoltTab.CAR, VoltRoute.SIGNALS, 2)
        assertEquals(PageMotion.PUSH, pageMotion(car, health))
        assertEquals(PageMotion.PUSH, pageMotion(health, signals))
        assertEquals(PageMotion.POP, pageMotion(signals, health))
        assertEquals(PageMotion.POP, pageMotion(health, car))
        // Choosing a tab from inside a pushed screen unwinds it like a back.
        assertEquals(PageMotion.POP, pageMotion(health, VoltPage(VoltTab.DRIVE, null, 0)))
        assertEquals(PageMotion.TAB, pageMotion(car, VoltPage(VoltTab.TRIPS, null, 0)))
        // Re-opening a screen already on the stack swaps the top at the same depth.
        assertEquals(PageMotion.PUSH, pageMotion(health, VoltPage(VoltTab.CAR, VoltRoute.SETTINGS, 1)))
    }

    private companion object {
        /** Two 16 ms frames a step: 30 fps clips. */
        const val FRAME_MS = 32L
        const val HOLD_MS = 400
        const val TAB_MS = 700

        /** Part-way through a screen slide. */
        const val MID_MS = 130
        const val SCREEN_MS = 600
        const val POLL_MS = 1_000
        const val PHASE_MS = 1_600
        const val SCAN_MS = 1_800

        /** A finger dragging down from a third of the way down the screen, past the refresh threshold. */
        const val PULL_FROM = 0.3f
        const val PULL_STEP = 0.03f
        const val PULL_STEPS = 14
    }
}
