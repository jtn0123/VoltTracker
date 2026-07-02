package com.volttracker.obdpoc

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused coverage for the drive-card renderer. Robolectric runs the graphics shadows in legacy
 * (non-rasterizing) mode here, so pixel reads are meaningless — instead these tests pin the layout
 * math directly (the stat grid must clear the brand line) and that degenerate inputs (zero-span
 * routes, oversized stat lists, overlong text) render without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripShareCardRendererTest {
    private fun route(vararg latLngs: Pair<Double, Double>): JSONObject {
        val points = JSONArray()
        for ((lat, lng) in latLngs) {
            points.put(JSONObject().put("lat", lat).put("lng", lng))
        }
        return JSONObject().put("points", points).put("pointCount", points.length())
    }

    private fun stats(count: Int): List<TripShareCardRenderer.CardStat> =
        List(count) { TripShareCardRenderer.CardStat("Stat $it", "Value $it") }

    @Test
    fun fullSixStatGridEndsAboveTheBrandGlyphs() {
        // The grid and the brand label are drawn independently; this is the invariant that keeps
        // "VoltTracker" from painting over the bottom stat row on a fully-logged EV drive (the
        // dashboard sends exactly MAX_STATS stats whenever all are present).
        assertTrue(
            "6-stat grid (${TripShareCardRenderer.statsGridBottom(6)}px) must end above the brand " +
                "glyphs (${TripShareCardRenderer.brandGlyphTop()}px)",
            TripShareCardRenderer.statsGridBottom(TripShareCardRenderer.MAX_STATS) <
                TripShareCardRenderer.brandGlyphTop(),
        )
    }

    @Test
    fun overflowingStatListsClampToMaxStats() {
        // Twice the stat budget must not grow the grid past the MAX_STATS footprint.
        assertEquals(
            TripShareCardRenderer.statsGridBottom(TripShareCardRenderer.MAX_STATS),
            TripShareCardRenderer.statsGridBottom(TripShareCardRenderer.MAX_STATS * 2),
            0.001f,
        )
    }

    @Test
    fun rendersAFullCardForATypicalDrive() {
        val card =
            TripShareCardRenderer.render(
                route(34.05 to -118.25, 34.06 to -118.24, 34.07 to -118.25),
                "Commute home",
                "Jun 30, 2026",
                stats(TripShareCardRenderer.MAX_STATS),
            )
        assertEquals(TripShareCardRenderer.SIZE, card.width)
        assertEquals(TripShareCardRenderer.SIZE, card.height)
        assertEquals(Bitmap.Config.ARGB_8888, card.config)
    }

    @Test
    fun degenerateRoutesAndOverflowingStatsStillRender() {
        // All points identical (zero lat/lng span, exercising the minimum-span floor) plus twice
        // the stat budget: the renderer must clamp and survive rather than divide by zero.
        val card =
            TripShareCardRenderer.render(
                route(34.05 to -118.25, 34.05 to -118.25, 34.05 to -118.25),
                "Stationary",
                "",
                stats(TripShareCardRenderer.MAX_STATS * 2),
            )
        assertEquals(TripShareCardRenderer.SIZE, card.width)
    }

    @Test
    fun emptyRoutesAndOverlongTextStillRender() {
        // No GPS points (route outline skipped) and pathological title/subtitle lengths: the
        // ellipsize loop must terminate and the card must still come out full size.
        val card =
            TripShareCardRenderer.render(
                route(),
                "A".repeat(500),
                "B".repeat(500),
                listOf(TripShareCardRenderer.CardStat("L".repeat(64), "V".repeat(64))),
            )
        assertEquals(TripShareCardRenderer.SIZE, card.width)
        assertEquals(TripShareCardRenderer.SIZE, card.height)
    }
}
