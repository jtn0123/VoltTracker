package com.volttracker.obdpoc.ui

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.map.MapTileLoader
import com.volttracker.obdpoc.map.StadiaTiles
import com.volttracker.obdpoc.map.TileId
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.trips.LocalMapTileLoader
import com.volttracker.obdpoc.ui.trips.TripMapCard
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Trips map's street-tile ground: no loader (no key) draws the plain ground only; a loader's
 * tiles are drawn under the route in the theme's style with the Stadia credit, fetched off the
 * main thread; failed tiles leave the plain ground. Also writes preview PNGs with fake street
 * tiles (`-ProborazziRecord`) — never real Stadia tiles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TripMapTilesTest {
    @get:Rule
    val compose = createComposeRule()

    /** Answers every tile with a fake street pattern, optionally only once asked off-thread. */
    private class FakeTiles(
        private val inMemory: Boolean,
        private val fail: Boolean = false,
    ) : MapTileLoader {
        val loads = CopyOnWriteArrayList<TileId>()
        val loadedOnMain = CopyOnWriteArrayList<Boolean>()
        val cachedAsks = CopyOnWriteArrayList<TileId>()

        override fun cached(tile: TileId): ImageBitmap? {
            cachedAsks += tile
            return if (inMemory) streets(tile.style) else null
        }

        override fun load(tile: TileId): ImageBitmap? {
            loads += tile
            loadedOnMain += Looper.myLooper() == Looper.getMainLooper()
            return if (fail) null else streets(tile.style)
        }
    }

    private fun show(
        loader: MapTileLoader?,
        appearance: AppearanceMode = AppearanceMode.DARK,
        darkStyle: DarkStyle = DarkStyle.OLED,
    ) {
        compose.setContent {
            VoltTheme(appearance, darkStyle) {
                CompositionLocalProvider(LocalMapTileLoader provides loader) {
                    TripMapCard(TripsUiState.demo)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun attributionShown() =
        compose.onAllNodesWithText(StadiaTiles.ATTRIBUTION).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun withoutALoaderThereAreNoTilesAndNoCredit() {
        show(loader = null)
        assertFalse(attributionShown())
    }

    @Test
    fun cachedTilesDrawAtOnceWithTheCreditInTheDarkStyle() {
        val tiles = FakeTiles(inMemory = true)
        show(tiles)
        assertTrue(attributionShown())
        assertTrue(tiles.cachedAsks.isNotEmpty())
        assertEquals(setOf(StadiaTiles.STYLE_DARK), tiles.cachedAsks.map { it.style }.toSet())
        // All in memory: nothing to fetch.
        assertTrue(tiles.loads.isEmpty())
    }

    @Test
    fun saddleUsesTheDarkStyle() {
        val saddle = FakeTiles(inMemory = true)
        show(saddle, darkStyle = DarkStyle.SADDLE)
        assertEquals(setOf(StadiaTiles.STYLE_DARK), saddle.cachedAsks.map { it.style }.toSet())
    }

    @Test
    fun latteUsesTheLightStyle() {
        val latte = FakeTiles(inMemory = true)
        show(latte, appearance = AppearanceMode.LIGHT)
        assertEquals(setOf(StadiaTiles.STYLE_LIGHT), latte.cachedAsks.map { it.style }.toSet())
    }

    @Test
    fun missingTilesLoadOffTheMainThreadThenAppear() {
        val tiles = FakeTiles(inMemory = false)
        show(tiles)
        compose.waitUntil(WAIT_MS) { attributionShown() }
        assertTrue(tiles.loads.isNotEmpty())
        assertEquals(tiles.loads.size, tiles.loads.toSet().size)
        assertFalse(tiles.loadedOnMain.any { it })
    }

    @Test
    fun failedTilesKeepThePlainGround() {
        val tiles = FakeTiles(inMemory = false, fail = true)
        show(tiles)
        compose.waitUntil(WAIT_MS) { tiles.loads.size == tiles.cachedAsks.toSet().size }
        compose.waitForIdle()
        assertFalse(attributionShown())
    }

    @Test
    fun previewOledTripsWithFakeStreetTiles() {
        capture("trips-tiles-oled", ThemeCase.OLED, FakeTiles(inMemory = true))
    }

    @Test
    fun previewLatteTripsWithFakeStreetTiles() {
        capture("trips-tiles-latte", ThemeCase.LATTE, FakeTiles(inMemory = true))
    }

    @Test
    fun previewTripsWithoutAKey() {
        capture("trips-nokey-oled", ThemeCase.OLED, null)
    }

    private fun capture(
        name: String,
        theme: ThemeCase,
        loader: MapTileLoader?,
    ) {
        val state = VoltAppUiState(trips = TripsUiState.demo, settings = theme.applyTo(SettingsUiState.demo))
        compose.setContent {
            CompositionLocalProvider(LocalMapTileLoader provides loader) {
                VoltApp(state, initialTab = VoltTab.TRIPS)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val TILE = 512
        const val BLOCK = 64f

        /** A stand-in street grid in roughly the style's colors — fake imagery, for previews only. */
        fun streets(style: String): ImageBitmap {
            val dark = style == StadiaTiles.STYLE_DARK
            val bitmap = ImageBitmap(TILE, TILE)
            val canvas = Canvas(bitmap)
            val ground = Paint().apply { color = if (dark) Color(0xFF1B1D22) else Color(0xFFEDEBE6) }
            canvas.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), ground)
            val road =
                Paint().apply {
                    color = if (dark) Color(0xFF30343C) else Color(0xFFFFFFFF)
                    strokeWidth = 6f
                }
            var at = BLOCK / 2
            while (at < TILE) {
                canvas.drawLine(Offset(at, 0f), Offset(at, TILE.toFloat()), road)
                canvas.drawLine(Offset(0f, at), Offset(TILE.toFloat(), at), road)
                at += BLOCK
            }
            return bitmap
        }
    }
}
