package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.map.LatLng
import com.volttracker.obdpoc.ui.map.MapScreen
import com.volttracker.obdpoc.ui.map.MapTileCompositor
import com.volttracker.obdpoc.ui.map.MapUiState
import com.volttracker.obdpoc.ui.map.MapViewMode
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Headless screenshot renders of the Compose Map screen. The demo render
 * fetches REAL basemap tiles (CARTO dark — the provider the legacy Leaflet
 * map used); when the tile CDN is unreachable the fallback grid renders
 * instead, so the test never fails on network.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Real CARTO tiles are opt-in (`-PvoltLiveTiles`, mapped to the
     * volt.screenshot.liveTiles system property): CI renders the
     * deterministic offline fallback grid; local screenshot-recording runs
     * pass the flag to capture the true basemap.
     */
    private fun composeDemoMap() =
        if (System.getProperty("volt.screenshot.liveTiles") == "true") {
            MapTileCompositor.compose(
                points = MapUiState.demo.route.map { LatLng(it.lat, it.lon) },
                // RoutePanel is 300dp tall and MapScreen pads 20dp per side off
                // the 412dp screen (372dp wide) — at 420dpi (density 2.625)
                // that is 977 x 788 px. Update if RoutePanel/MapScreen change.
                widthPx = 977,
                heightPx = 788,
            )
        } else {
            null
        }

    // Taller-than-viewport render so the full scrollable content is visible in
    // the before/after review images (the real screen scrolls).
    @Test
    @Config(qualifiers = "w412dp-h1560dp-420dpi")
    fun mapScreenDemoState() {
        val composedMap = composeDemoMap()
        compose.setContent {
            VoltTheme { MapScreen(MapUiState.demo, composedMap = composedMap) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-map.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h1560dp-420dpi")
    fun mapScreenHeatMode() {
        val composedMap = composeDemoMap()
        compose.setContent {
            VoltTheme {
                MapScreen(
                    MapUiState.demo.copy(viewMode = MapViewMode.HEAT),
                    composedMap = composedMap,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-map-heat.png")
    }

    @Test
    fun mapScreenEmptyState() {
        compose.setContent {
            VoltTheme { MapScreen(MapUiState()) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-map-empty.png")
    }
}
