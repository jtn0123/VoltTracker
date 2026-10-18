package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltGearTest {
    @Test
    fun confirmedCodesFollowTheLeverOrder() {
        val park = VoltGear.decode(8)!!
        assertEquals("P", park.letter)
        assertEquals(GearConfidence.CONFIRMED, park.confidence)
        assertTrue(park.isPark)
        assertFalse(park.isNonPark)

        mapOf(7 to "R", 6 to "N", 5 to "D", 3 to "L").forEach { (raw, letter) ->
            val gear = VoltGear.decode(raw)!!
            assertEquals("code $raw", letter, gear.letter)
            assertEquals("code $raw", GearConfidence.CONFIRMED, gear.confidence)
            assertEquals(raw, gear.raw)
            assertTrue("code $raw is a non-Park gear", gear.isNonPark)
        }
    }

    @Test
    fun theHardRegenCodeReadsAsATentativeLow() {
        val gear = VoltGear.decode(2)!!
        assertEquals("L", gear.letter)
        assertEquals(GearConfidence.TENTATIVE, gear.confidence)
        assertTrue(gear.isNonPark)
    }

    @Test
    fun unseenCodesStayUnknownInsteadOfGuessed() {
        for (raw in listOf(0, 1, 4, 9, 13, 255)) {
            val gear = VoltGear.decode(raw)!!
            assertEquals("?", gear.letter)
            assertEquals(GearConfidence.UNKNOWN, gear.confidence)
            assertEquals("raw is kept for confirming the table later", raw, gear.raw)
            assertFalse(gear.isPark)
            assertFalse("unknown is neither Park nor a driving gear", gear.isNonPark)
            assertFalse(VoltGear.isKnownNonPark(raw))
        }
    }

    @Test
    fun missingReadingDecodesToNull() {
        assertNull(VoltGear.decode(null))
        assertFalse(VoltGear.isPark(null))
        assertFalse(VoltGear.isKnownNonPark(null))
    }

    @Test
    fun displayTextShowsUnknownCodesWithTheirNumber() {
        assertEquals("D", VoltGear.displayText("D", 5))
        assertEquals("? (code 13)", VoltGear.displayText("?", 13))
        assertEquals("?", VoltGear.displayText("?", null))
    }

    @Test
    fun helperPredicates() {
        assertTrue(VoltGear.isPark(VoltGear.PARK_RAW))
        assertFalse(VoltGear.isPark(5))
        assertTrue(VoltGear.isKnownNonPark(5))
        assertFalse(VoltGear.isKnownNonPark(8))
        assertEquals("confirmed", GearConfidence.CONFIRMED.wireName)
        assertEquals("tentative", GearConfidence.TENTATIVE.wireName)
        assertEquals("unknown", GearConfidence.UNKNOWN.wireName)
    }
}
