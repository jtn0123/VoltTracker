package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.map.MapUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every tab and pushed route (Settings, Health) of the whole [VoltApp] at phone size, in the dark
 * and the light theme — the before/after preview set for UI changes (`-ProborazziRecord` writes
 * the PNGs under build/outputs/roborazzi/app-<page>-<theme>.png), and a smoke proof each page
 * composes in both.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AppPreviewScreenshotTest(
    private val page: String,
    private val tab: VoltTab,
    private val routes: List<VoltRoute>,
    private val appearance: AppearanceMode,
) {
    @get:Rule
    val compose = createComposeRule()

    private fun demo(appearance: AppearanceMode) =
        VoltAppUiState(
            drive = DriveUiState.demo,
            charge = ChargeUiState.demo,
            map = MapUiState.demo,
            insights = InsightsUiState.demo,
            diag = DiagUiState.demo,
            settings = SettingsUiState.demo.copy(appearance = appearance),
        )

    @Test
    fun capture() {
        compose.setContent { VoltApp(demo(appearance), initialTab = tab, initialRoutes = routes) }
        compose
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/app-$page-${appearance.key}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{3}")
        fun cases(): List<Array<Any>> {
            val pages =
                VoltTab.entries.map { Triple(it.name.lowercase(), it, emptyList<VoltRoute>()) } +
                    listOf(
                        Triple("settings", VoltTab.CAR, listOf(VoltRoute.SETTINGS)),
                        Triple("health", VoltTab.CAR, listOf(VoltRoute.HEALTH)),
                    )
            return pages.flatMap { (page, tab, routes) ->
                listOf(AppearanceMode.DARK, AppearanceMode.LIGHT).map { arrayOf<Any>(page, tab, routes, it) }
            }
        }
    }
}
