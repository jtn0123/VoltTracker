package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ObdStoreRouteProjection.simplifyRoutePoints] (Douglas-Peucker): collinear runs collapse,
 * corners sharper than the tolerance survive, and the endpoints (whose timestamps route/trip ids
 * are built from) are always kept.
 */
class ObdStoreRouteSimplifyTest {
    @Test
    fun collinearPointsCollapseToTheEndpoints() {
        // A dead-straight line north: every interior point deviates 0 m from the end chord.
        val points = JSONArray()
        for (i in 0 until 50) {
            points.put(point(1000L + i, 32.70 + i * 0.0001, -117.10))
        }

        val simplified = ObdStoreRouteProjection.simplifyRoutePoints(points, TOLERANCE_METERS)

        assertEquals(2, simplified.length())
        assertEquals(1000L, simplified.getJSONObject(0).getLong("atMs"))
        assertEquals(1049L, simplified.getJSONObject(1).getLong("atMs"))
    }

    @Test
    fun cornerPointsAreRetained() {
        // An L-shape: east along constant latitude, then north along constant longitude. The
        // corner deviates hundreds of meters from the end-to-end chord and must survive.
        val points = JSONArray()
        for (i in 0 until 10) {
            points.put(point(1000L + i, 32.70, -117.10 + i * 0.001))
        }
        for (i in 1 until 10) {
            points.put(point(1009L + i, 32.70 + i * 0.001, -117.10 + 9 * 0.001))
        }

        val simplified = ObdStoreRouteProjection.simplifyRoutePoints(points, TOLERANCE_METERS)

        assertEquals(3, simplified.length())
        assertEquals("first point kept", 1000L, simplified.getJSONObject(0).getLong("atMs"))
        assertEquals("corner kept", 1009L, simplified.getJSONObject(1).getLong("atMs"))
        assertEquals("last point kept", 1018L, simplified.getJSONObject(2).getLong("atMs"))
    }

    @Test
    fun deviationsUnderToleranceAreDroppedAndOverToleranceKept() {
        // One ~55 m corner (kept) with an intermediate point only ~5 m off the corner's chord
        // (dropped): point 2 sits near the midpoint of the p1->p3 chord, nudged 0.00005 deg.
        val smallOffsetDeg = 0.00005 // ~5.5 m of latitude
        val bigOffsetDeg = 0.0005 // ~55 m of latitude
        val points = JSONArray()
        points.put(point(1L, 32.70, -117.10))
        points.put(point(2L, 32.70 + bigOffsetDeg / 2 + smallOffsetDeg, -117.09))
        points.put(point(3L, 32.70 + bigOffsetDeg, -117.08))
        points.put(point(4L, 32.70, -117.07))

        val simplified = ObdStoreRouteProjection.simplifyRoutePoints(points, TOLERANCE_METERS)

        assertEquals(3, simplified.length())
        assertEquals(1L, simplified.getJSONObject(0).getLong("atMs"))
        assertEquals(3L, simplified.getJSONObject(1).getLong("atMs"))
        assertEquals(4L, simplified.getJSONObject(2).getLong("atMs"))
    }

    @Test
    fun twoOrFewerPointsPassThroughUntouched() {
        val pair = JSONArray()
        pair.put(point(1L, 32.70, -117.10))
        pair.put(point(2L, 32.71, -117.10))

        assertEquals(2, ObdStoreRouteProjection.simplifyRoutePoints(pair, TOLERANCE_METERS).length())
        assertEquals(0, ObdStoreRouteProjection.simplifyRoutePoints(JSONArray(), TOLERANCE_METERS).length())
    }

    @Test
    fun simplifiedPointsKeepTheirFullPayload() {
        // Kept points must be the original objects (timestamps + extras), not rebuilt ones.
        val points = JSONArray()
        points.put(point(1L, 32.70, -117.10).put("accuracyM", 4.5))
        points.put(point(2L, 32.70 + 0.001, -117.09))
        points.put(point(3L, 32.70, -117.08).put("accuracyM", 6.5))

        val simplified = ObdStoreRouteProjection.simplifyRoutePoints(points, TOLERANCE_METERS)

        assertEquals(3, simplified.length())
        assertEquals(4.5, simplified.getJSONObject(0).getDouble("accuracyM"), 1e-9)
        assertEquals(6.5, simplified.getJSONObject(2).getDouble("accuracyM"), 1e-9)
        assertTrue(simplified.getJSONObject(1).getDouble("lat") > 32.70)
    }

    private fun point(
        atMs: Long,
        lat: Double,
        lng: Double,
    ): JSONObject =
        JSONObject()
            .put("atMs", atMs)
            .put("lat", lat)
            .put("lng", lng)

    private companion object {
        const val TOLERANCE_METERS = ObdStoreRouteProjection.SIMPLIFY_TOLERANCE_METERS
    }
}
