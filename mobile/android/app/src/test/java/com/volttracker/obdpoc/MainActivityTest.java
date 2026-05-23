package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the pure helper logic split out of {@link MainActivity} into {@link MainActivityUtils} —
 * JSON parsing, connection-state classification, value coalescing, and adapter-address redaction.
 */
public class MainActivityTest {

    @Test
    public void parseJsonReadsValidObjects() {
        assertEquals(1, MainActivityUtils.parseJson("{\"a\":1}").optInt("a"));
    }

    @Test
    public void parseJsonReturnsEmptyForNullOrGarbage() {
        assertEquals(0, MainActivityUtils.parseJson(null).length());
        assertEquals(0, MainActivityUtils.parseJson("").length());
        assertEquals(0, MainActivityUtils.parseJson("not json").length());
    }

    @Test
    public void isConnectedStateRecognisesActiveStates() {
        assertTrue(MainActivityUtils.isConnectedState("connected"));
        assertTrue(MainActivityUtils.isConnectedState("CONNECTED"));
        assertTrue(MainActivityUtils.isConnectedState("connecting"));
        assertTrue(MainActivityUtils.isConnectedState("initializing"));
        assertTrue(MainActivityUtils.isConnectedState("scanning"));
        assertTrue(MainActivityUtils.isConnectedState("scan-complete"));
        assertTrue(MainActivityUtils.isConnectedState("demo"));
    }

    @Test
    public void isConnectedStateRejectsIdleStates() {
        assertFalse(MainActivityUtils.isConnectedState("idle"));
        assertFalse(MainActivityUtils.isConnectedState("ready"));
        assertFalse(MainActivityUtils.isConnectedState(null));
    }

    @Test
    public void coalesceReturnsFirstNonBlank() {
        assertEquals("a", MainActivityUtils.coalesce("a", "b", "c"));
        assertEquals("b", MainActivityUtils.coalesce("", "b", "c"));
        assertEquals("b", MainActivityUtils.coalesce("   ", "b", "c"));
        assertEquals("c", MainActivityUtils.coalesce(null, null, "c"));
        assertEquals("", MainActivityUtils.coalesce(null, null, null));
    }

    @Test
    public void redactAddressKeepsOnlyTheLastFiveChars() {
        assertEquals("...44:55", MainActivityUtils.redactAddress("00:11:22:33:44:55"));
    }

    @Test
    public void redactAddressDropsShortOrMissingAddresses() {
        assertEquals("", MainActivityUtils.redactAddress("ab"));
        assertEquals("", MainActivityUtils.redactAddress(null));
    }
}
