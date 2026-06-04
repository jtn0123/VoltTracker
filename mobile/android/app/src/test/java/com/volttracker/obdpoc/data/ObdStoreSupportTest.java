package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pure-JVM tests for the stateless helpers extracted into {@link ObdStoreSupport}. Splitting them
 * out of {@code ObdLocalStore} is what makes this coverage possible.
 */
public class ObdStoreSupportTest {

    @Test
    public void cleanTrimsAndTolueratesNull() {
        assertEquals("", ObdStoreSupport.clean(null));
        assertEquals("x", ObdStoreSupport.clean("  x  "));
        assertEquals("", ObdStoreSupport.clean("   "));
    }

    @Test
    public void boundedLimitClampsToOneThroughFiveHundred() {
        assertEquals("1", ObdStoreSupport.boundedLimit(0));
        assertEquals("1", ObdStoreSupport.boundedLimit(-20));
        assertEquals("50", ObdStoreSupport.boundedLimit(50));
        assertEquals("500", ObdStoreSupport.boundedLimit(9999));
    }

    @Test
    public void cleanModeNormalizesToAKnownMode() {
        assertEquals("obd", ObdStoreSupport.cleanMode("obd"));
        assertEquals("scan", ObdStoreSupport.cleanMode("SCAN"));
        assertEquals("demo", ObdStoreSupport.cleanMode(" demo "));
        // anything unrecognised falls back to obd
        assertEquals("obd", ObdStoreSupport.cleanMode("garbage"));
        assertEquals("obd", ObdStoreSupport.cleanMode(null));
    }

    @Test
    public void cleanStatusNormalizesAndDefaults() {
        assertEquals("error", ObdStoreSupport.cleanStatus("ERROR"));
        assertEquals("active", ObdStoreSupport.cleanStatus("active"));
        // blank defaults to complete
        assertEquals("complete", ObdStoreSupport.cleanStatus(""));
        assertEquals("complete", ObdStoreSupport.cleanStatus(null));
    }

    @Test
    public void adapterKeyPrefersTheAddress() {
        assertEquals("AA:BB:CC", ObdStoreSupport.adapterKey("aa:bb:cc", "obd"));
        assertEquals("demo", ObdStoreSupport.adapterKey("", "demo"));
        assertEquals("unknown", ObdStoreSupport.adapterKey("", "obd"));
        assertEquals("unknown", ObdStoreSupport.adapterKey(null, "obd"));
    }

    @Test
    public void chooseLatestPrefersANonBlankCandidate() {
        assertEquals("new", ObdStoreSupport.chooseLatest("new", "old"));
        assertEquals("old", ObdStoreSupport.chooseLatest("   ", "old"));
        assertEquals("old", ObdStoreSupport.chooseLatest(null, "old"));
    }

    @Test
    public void isUsefulTelemetryDetectsRealReadings() throws JSONException {
        assertFalse(ObdStoreSupport.isUsefulTelemetry(new JSONObject()));
        assertFalse(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("note", "hi")));
        assertFalse(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("source", "obd")));
        assertFalse(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("raw", "NO DATA")));
        assertTrue(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("speedKph", 40)));
        assertTrue(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("voltage", 13.8)));
        assertTrue(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("latitude", 32.7)));
        assertTrue(ObdStoreSupport.isUsefulTelemetry(new JSONObject().put("clearDtcOk", true)));
    }

    @Test
    public void optTimestampFallsBackWhenAbsent() {
        assertEquals(99L, ObdStoreSupport.optTimestamp(new JSONObject(), "k", 99L));
        assertEquals(5L, ObdStoreSupport.optTimestamp(jsonWith("k", 5L), "k", 99L));
    }

    @Test
    public void parseObjectTreatsGarbageAsEmpty() {
        assertEquals(0, ObdStoreSupport.parseObject(null).length());
        assertEquals(0, ObdStoreSupport.parseObject("not json").length());
        assertEquals(1, ObdStoreSupport.parseObject("{\"a\":1}").optInt("a"));
    }

    @Test
    public void reverseFlipsArrayOrder() throws JSONException {
        JSONArray source = new JSONArray().put(1).put(2).put(3);
        JSONArray reversed = ObdStoreSupport.reverse(source);
        assertEquals(3, reversed.getInt(0));
        assertEquals(2, reversed.getInt(1));
        assertEquals(1, reversed.getInt(2));
    }

    private static JSONObject jsonWith(String key, long value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
