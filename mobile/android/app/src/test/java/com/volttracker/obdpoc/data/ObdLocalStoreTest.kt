package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure route-distance math behind the Trips and Insights aggregates. One degree of
 * latitude is ~111.195 km for the 6371 km earth radius used by the store.
 */
class ObdLocalStoreTest {
    @Test
    fun haversineForOneDegreeLatitude() {
        assertEquals(ONE_DEGREE_METERS, ObdStoreSupport.haversineMeters(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test
    fun haversineForOneDegreeLongitudeAtEquator() {
        assertEquals(ONE_DEGREE_METERS, ObdStoreSupport.haversineMeters(0.0, 0.0, 0.0, 1.0), 1.0)
    }

    @Test
    fun haversineForIdenticalPointIsZero() {
        assertEquals(0.0, ObdStoreSupport.haversineMeters(34.05, -118.25, 34.05, -118.25), 0.001)
    }

    @Test
    @Throws(JSONException::class)
    fun routeDistanceSumsConsecutiveLegs() {
        val points = points(point(0.0, 0.0), point(0.0, 1.0), point(1.0, 1.0))
        // Leg 1: (0,0)->(0,1) and leg 2: (0,1)->(1,1), each ~one degree.
        assertEquals(ONE_DEGREE_METERS * 2, ObdStoreSupport.distanceMeters(points), 5.0)
    }

    @Test
    @Throws(JSONException::class)
    fun routeDistanceIsZeroForEmptyOrSinglePoint() {
        assertEquals(0.0, ObdStoreSupport.distanceMeters(JSONArray()), 0.001)
        assertEquals(0.0, ObdStoreSupport.distanceMeters(points(point(40.7, -74.0))), 0.001)
    }

    @Test
    fun invalidCoordinatesAreExcludedFromRouteDistanceAndBounds() {
        val points =
            JSONArray(
                """
                [
                    {"lat":10.0,"lng":10.0},
                    {"lat":91.0,"lng":10.0},
                    {"lat":10.0,"lng":11.0},
                    {"lat":1e9999,"lng":12.0}
                ]
                """.trimIndent(),
            )

        // An invalid point breaks continuity, so the valid points on either side must not be
        // joined into an invented route leg. Invalid values must also stay out of JSON bounds.
        assertEquals(0.0, ObdStoreSupport.distanceMeters(points), 0.001)
        val bounds = ObdStoreSupport.boundsFor(points)
        assertEquals(10.0, bounds.getDouble("minLat"), 0.0)
        assertEquals(10.0, bounds.getDouble("maxLat"), 0.0)
        assertEquals(10.0, bounds.getDouble("minLng"), 0.0)
        assertEquals(11.0, bounds.getDouble("maxLng"), 0.0)
    }

    @Throws(JSONException::class)
    private fun point(
        lat: Double,
        lng: Double,
    ): JSONObject {
        val point = JSONObject()
        point.put("lat", lat)
        point.put("lng", lng)
        return point
    }

    private fun points(vararg entries: JSONObject): JSONArray {
        val array = JSONArray()
        for (entry in entries) {
            array.put(entry)
        }
        return array
    }

    companion object {
        private const val ONE_DEGREE_METERS = 111194.9
    }
}
