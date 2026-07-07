package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit coverage for bridge input clamping shared by connection, export, and diagnostics paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeInputTest {
    @Test
    fun bridgeSafeTrimsNullsAndShortValues() {
        assertEquals("", bridgeSafe(null, BRIDGE_MAX_LABEL_LEN))
        assertEquals("", bridgeSafe("   ", BRIDGE_MAX_LABEL_LEN))
        assertEquals("route:1", bridgeSafe("  route:1  ", BRIDGE_MAX_LABEL_LEN))
    }

    @Test
    fun bridgeSafeClampsLongValuesWithoutSplittingSurrogatePairs() {
        assertEquals("abc", bridgeSafe("abcdef", 3))
        assertEquals("ab", bridgeSafe("ab\uD83D\uDE00cd", 3))
        assertEquals("ab\uD83D\uDE00", bridgeSafe("ab\uD83D\uDE00cd", 4))
        assertEquals("", bridgeSafe("\uD83D\uDE00", 1))
    }

    @Test
    fun parseBridgePositiveIdAcceptsOnlyPositiveWholeNumbersAfterSanitizing() {
        assertEquals(42L, parseBridgePositiveId(" 42 "))
        assertEquals(-1L, parseBridgePositiveId(null))
        assertEquals(-1L, parseBridgePositiveId(""))
        assertEquals(-1L, parseBridgePositiveId("0"))
        assertEquals(-1L, parseBridgePositiveId("-7"))
        assertEquals(-1L, parseBridgePositiveId("not-a-number"))
        assertEquals(-1L, parseBridgePositiveId("9".repeat(BRIDGE_MAX_LABEL_LEN + 1)))
    }

    @Test
    fun validBridgeBluetoothAddressTrimsBeforeValidation() {
        assertTrue(validBridgeBluetoothAddress(" 00:11:22:33:AA:FF "))
        assertFalse(validBridgeBluetoothAddress("00:11:22:33:AA"))
        assertFalse(validBridgeBluetoothAddress(null))
    }
}
