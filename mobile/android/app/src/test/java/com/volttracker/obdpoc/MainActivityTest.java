package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the pure helper logic in {@link MainActivity} — JSON parsing, connection-state
 * classification, value coalescing, and adapter-address redaction.
 */
public class MainActivityTest {

    @Test
    public void parseJsonReadsValidObjects() {
        assertEquals(1, MainActivity.parseJson("{\"a\":1}").optInt("a"));
    }

    @Test
    public void parseJsonReturnsEmptyForNullOrGarbage() {
        assertEquals(0, MainActivity.parseJson(null).length());
        assertEquals(0, MainActivity.parseJson("").length());
        assertEquals(0, MainActivity.parseJson("not json").length());
    }

    @Test
    public void isConnectedStateRecognisesActiveStates() {
        assertTrue(MainActivity.isConnectedState("connected"));
        assertTrue(MainActivity.isConnectedState("CONNECTED"));
        assertTrue(MainActivity.isConnectedState("connecting"));
        assertTrue(MainActivity.isConnectedState("initializing"));
        assertTrue(MainActivity.isConnectedState("scanning"));
        assertTrue(MainActivity.isConnectedState("scan-complete"));
        assertTrue(MainActivity.isConnectedState("demo"));
    }

    @Test
    public void isConnectedStateRejectsIdleStates() {
        assertFalse(MainActivity.isConnectedState("idle"));
        assertFalse(MainActivity.isConnectedState("ready"));
        assertFalse(MainActivity.isConnectedState(null));
    }

    @Test
    public void coalesceReturnsFirstNonBlank() {
        assertEquals("a", MainActivity.coalesce("a", "b", "c"));
        assertEquals("b", MainActivity.coalesce("", "b", "c"));
        assertEquals("b", MainActivity.coalesce("   ", "b", "c"));
        assertEquals("c", MainActivity.coalesce(null, null, "c"));
        assertEquals("", MainActivity.coalesce(null, null, null));
    }

    @Test
    public void redactAddressKeepsOnlyTheLastFiveChars() {
        assertEquals("...44:55", MainActivity.redactAddress("00:11:22:33:44:55"));
    }

    @Test
    public void redactAddressDropsShortOrMissingAddresses() {
        assertEquals("", MainActivity.redactAddress("ab"));
        assertEquals("", MainActivity.redactAddress(null));
    }
}
