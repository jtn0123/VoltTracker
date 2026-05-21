package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.junit.Test;

/**
 * Tests the pure route-distance math behind the Trips and Insights aggregates.
 * One degree of latitude is ~111.195 km for the 6371 km earth radius used by the store.
 */
public class ObdLocalStoreTest {

    private static final double ONE_DEGREE_METERS = 111194.9;

    @Test
    public void haversineForOneDegreeLatitude() {
        assertEquals(ONE_DEGREE_METERS, ObdLocalStore.haversineMeters(0, 0, 1, 0), 1.0);
    }

    @Test
    public void haversineForOneDegreeLongitudeAtEquator() {
        assertEquals(ONE_DEGREE_METERS, ObdLocalStore.haversineMeters(0, 0, 0, 1), 1.0);
    }

    @Test
    public void haversineForIdenticalPointIsZero() {
        assertEquals(0.0, ObdLocalStore.haversineMeters(34.05, -118.25, 34.05, -118.25), 0.001);
    }

    @Test
    public void routeDistanceSumsConsecutiveLegs() throws JSONException {
        JSONArray points = points(
                point(0, 0),
                point(0, 1),
                point(1, 1));
        // Leg 1: (0,0)->(0,1) and leg 2: (0,1)->(1,1), each ~one degree.
        assertEquals(ONE_DEGREE_METERS * 2, ObdLocalStore.distanceMeters(points), 5.0);
    }

    @Test
    public void routeDistanceIsZeroForEmptyOrSinglePoint() throws JSONException {
        assertEquals(0.0, ObdLocalStore.distanceMeters(new JSONArray()), 0.001);
        assertEquals(0.0, ObdLocalStore.distanceMeters(points(point(40.7, -74.0))), 0.001);
    }

    private static JSONObject point(double lat, double lng) throws JSONException {
        JSONObject point = new JSONObject();
        point.put("lat", lat);
        point.put("lng", lng);
        return point;
    }

    private static JSONArray points(JSONObject... entries) {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) {
            array.put(entry);
        }
        return array;
    }
}
