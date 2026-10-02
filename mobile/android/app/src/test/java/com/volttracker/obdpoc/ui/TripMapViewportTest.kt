package com.volttracker.obdpoc.ui

import androidx.compose.ui.geometry.Size
import com.volttracker.obdpoc.ui.trips.MapViewport
import com.volttracker.obdpoc.ui.trips.TripPoint
import com.volttracker.obdpoc.ui.trips.fitViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.pow

/** The Trips map's Web-Mercator viewport: the route and the basemap tiles must share one projection. */
class TripMapViewportTest {
    private val card = Size(1000f, 700f)

    // A short drive in San Diego (about 5 km across).
    private val route =
        listOf(
            TripPoint(32.70, -117.16, 0L),
            TripPoint(32.72, -117.14, 1L),
            TripPoint(32.74, -117.12, 2L),
        )

    private fun fit(
        points: List<TripPoint> = route,
        size: Size = card,
    ) = fitViewport(points, size, padSide = 70f, padTop = 60f, padBottom = 200f, tileSizePx = 512f)

    @Test
    fun worldCoordinatesAreWebMercator() {
        assertEquals(0.5, MapViewport.worldX(0.0), 1e-12)
        assertEquals(0.0, MapViewport.worldX(-180.0), 1e-12)
        assertEquals(1.0, MapViewport.worldX(180.0), 1e-12)
        assertEquals(0.5, MapViewport.worldY(0.0), 1e-12)
        // The Mercator limit maps to the top / bottom edge, and beyond it clamps.
        assertEquals(0.0, MapViewport.worldY(85.05112878), 1e-6)
        assertEquals(1.0, MapViewport.worldY(-85.05112878), 1e-6)
        assertEquals(MapViewport.worldY(85.05112878), MapViewport.worldY(89.9), 1e-12)
        // Tile (z=12) holding downtown San Diego, per the standard slippy-map formula.
        val z = 12
        assertEquals(714, floor(MapViewport.worldX(-117.16) * 2.0.pow(z)).toInt())
        assertEquals(1653, floor(MapViewport.worldY(32.72) * 2.0.pow(z)).toInt())
    }

    @Test
    fun theRouteIsCentredInsideThePadding() {
        val viewport = fit()
        val xs = route.map { viewport.project(it).x }
        val ys = route.map { viewport.project(it).y }
        // Centred in the padded box (70..930 × 60..500).
        assertEquals(500f, (xs.min() + xs.max()) / 2, 0.5f)
        assertEquals(280f, (ys.min() + ys.max()) / 2, 0.5f)
        // One axis fills its box; the other fits inside it.
        val fillsWidth = xs.max() - xs.min() > 859f
        val fillsHeight = ys.max() - ys.min() > 439f
        assertTrue(fillsWidth || fillsHeight)
        assertTrue(xs.min() >= 69.5f && xs.max() <= 930.5f && ys.min() >= 59.5f && ys.max() <= 500.5f)
        // North is up.
        assertTrue(viewport.project(route.last()).y < viewport.project(route.first()).y)
    }

    @Test
    fun theTileZoomIsTheDeepestWhoseTilesAreNoSmallerThanNominal() {
        val viewport = fit()
        assertTrue(viewport.tilePx >= 512.0 && viewport.tilePx < 1024.0)
        assertEquals(viewport.scale, viewport.tilePx * 2.0.pow(viewport.zoom), 1e-6)
    }

    @Test
    fun eachPointLandsOnTheTileThatContainsIt() {
        val viewport = fit()
        val slots = viewport.tiles()
        for (p in route) {
            val n = 2.0.pow(viewport.zoom)
            val tx = floor(MapViewport.worldX(p.lon) * n).toInt()
            val ty = floor(MapViewport.worldY(p.lat) * n).toInt()
            val slot = slots.single { it.x == tx && it.y == ty }
            val o = viewport.project(p)
            assertEquals(viewport.zoom, slot.z)
            assertTrue(o.x >= slot.topLeft.x - 0.5f && o.x <= slot.topLeft.x + slot.sizePx + 0.5f)
            assertTrue(o.y >= slot.topLeft.y - 0.5f && o.y <= slot.topLeft.y + slot.sizePx + 0.5f)
        }
    }

    @Test
    fun theTilesCoverTheWholeCardWithoutGaps() {
        val viewport = fit()
        val slots = viewport.tiles()
        assertTrue(slots.minOf { it.topLeft.x } <= 0f)
        assertTrue(slots.minOf { it.topLeft.y } <= 0f)
        assertTrue(slots.maxOf { it.topLeft.x + it.sizePx } >= card.width)
        assertTrue(slots.maxOf { it.topLeft.y + it.sizePx } >= card.height)
        // Neighbours abut exactly.
        val byX = slots.map { it.x }.distinct().sorted()
        val left = slots.first { it.x == byX[0] }
        val right = slots.first { it.x == byX[1] && it.y == left.y }
        assertEquals(left.topLeft.x + left.sizePx, right.topLeft.x, 0.01f)
        assertEquals(byX.size * slots.map { it.y }.distinct().size, slots.size)
    }

    @Test
    fun aStationaryTrackStopsAtTheMaxZoom() {
        val parked = listOf(TripPoint(32.7, -117.1, 0L), TripPoint(32.7, -117.1, 1L))
        val viewport = fit(parked)
        assertEquals(MapViewport.MAX_ZOOM, viewport.zoom)
        assertEquals(512.0, viewport.tilePx, 1e-6)
        val o = viewport.project(parked[0])
        assertEquals(500f, o.x, 0.5f)
        assertEquals(280f, o.y, 0.5f)
    }

    @Test
    fun aWorldSizedTrackZoomsOutAndClampsTilesToTheWorld() {
        val acrossTheWorld = listOf(TripPoint(60.0, -170.0, 0L), TripPoint(-50.0, 170.0, 1L))
        val viewport = fit(acrossTheWorld, Size(300f, 400f))
        assertEquals(0, viewport.zoom)
        val slots = viewport.tiles()
        assertEquals(listOf(0 to 0), slots.map { it.x to it.y })
    }
}
