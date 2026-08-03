package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Headless screenshot renders of the Compose Charge screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChargeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Taller-than-viewport render so the full scrollable content is visible in
    // the before/after review images (the real screen scrolls).
    @Test
    @Config(qualifiers = "w412dp-h1300dp-420dpi")
    fun chargeScreenDemoState() {
        compose.setContent {
            VoltTheme { ChargeScreen(ChargeUiState.demo) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-charge.png")
    }

    @Test
    fun chargeScreenNoCellBalanceState() {
        compose.setContent {
            VoltTheme {
                ChargeScreen(ChargeUiState.demo.copy(cellBalanceLabel = null))
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-charge-no-cell-balance.png")
    }

    @Test
    fun chargeScreenIdleState() {
        compose.setContent {
            VoltTheme {
                ChargeScreen(
                    ChargeUiState.demo.copy(
                        charging = false,
                        timeToFullLabel = null,
                        statusLabel = "Idle",
                        connected = false,
                    ),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-charge-idle.png")
    }
}
