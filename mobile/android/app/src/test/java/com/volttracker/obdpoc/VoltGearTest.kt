package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltGearTest {
    @Test
    fun confirmedCodesDecodeToParkAndDrive() {
        val park = VoltGear.decode(8)!!
        assertEquals("P", park.letter)
        assertEquals(GearConfidence.CONFIRMED, park.confidence)
        assertTrue(park.isPark)
        assertFalse(park.isNonPark)

        val drive = VoltGear.decode(3)!!
        assertEquals("D", drive.letter)
        assertEquals(GearConfidence.CONFIRMED, drive.confidence)
        assertTrue(drive.isNonPark)
    }

    @Test
    fun tentativeCodesAreMarkedTentative() {
        mapOf(7 to "R", 6 to "N", 2 to "L", 5 to "L").forEach { (raw, letter) ->
            val gear = VoltGear.decode(raw)!!
            assertEquals("code $raw", letter, gear.letter)
            assertEquals("code $raw", GearConfidence.TENTATIVE, gear.confidence)
            assertEquals(raw, gear.raw)
            assertTrue("code $raw is a non-Park gear", gear.isNonPark)
        }
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
        assertEquals("D", VoltGear.displayText("D", 3))
        assertEquals("? (code 13)", VoltGear.displayText("?", 13))
        assertEquals("?", VoltGear.displayText("?", null))
    }

    @Test
    fun helperPredicates() {
        assertTrue(VoltGear.isPark(VoltGear.PARK_RAW))
        assertFalse(VoltGear.isPark(3))
        assertTrue(VoltGear.isKnownNonPark(3))
        assertFalse(VoltGear.isKnownNonPark(8))
        assertEquals("confirmed", GearConfidence.CONFIRMED.wireName)
        assertEquals("tentative", GearConfidence.TENTATIVE.wireName)
        assertEquals("unknown", GearConfidence.UNKNOWN.wireName)
    }
}
