package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcmTirePressureTest {
    private fun kpa(
        command: String,
        response: String?,
    ): Double? = ObdProtocol.parseKnownValue(command, response)?.valueNumeric

    @Test
    fun eachWheelIsAWordInSixteenthsOfAKpa() {
        // 0x0F00 = 3840 / 16 = 240 kPa (34.8 psi); the layout carries one word per wheel.
        assertEquals(240.0, kpa("225008", "641 05 62 50 08 0F 00\r>")!!, 1e-9)
        assertEquals(244.0, kpa("225007", "62 50 07 0F 40")!!, 1e-9)
        assertEquals(248.0, kpa("225006", "6250060F80")!!, 1e-9)
        assertEquals(252.0, kpa("225005", "6250050FC0")!!, 1e-9)
        assertEquals("1/16 kPa resolution", 240.0625, kpa("225008", "6250080F01")!!, 1e-9)
    }

    @Test
    fun wheelsMapToTheSwcanTireKeys() {
        val keys = BcmTirePressure.Wheel.entries.associate { it.command to it.sampleKey }
        assertEquals(
            mapOf(
                "225008" to "tirePressureFlKpa",
                "225007" to "tirePressureRlKpa",
                "225006" to "tirePressureFrKpa",
                "225005" to "tirePressureRrKpa",
            ),
            keys,
        )
    }

    @Test
    fun aMissingSensorRefusalsAndNonsenseAreNoReading() {
        assertNull("no reading", kpa("225008", "62 50 08 FF FF"))
        assertNull(kpa("225008", null))
        assertNull(kpa("225008", "NO DATA"))
        assertNull("negative reply", kpa("225008", "7F 22 31"))
        assertNull("one byte is not a word", kpa("225008", "62 50 08 0F"))
        // 0xFFFE / 16 = 4095.9 kPa: no tire holds that.
        assertNull("implausible", kpa("225008", "62 50 08 FF FE"))
    }

    @Test
    fun catalogListsEachWheelAtTheBodyModule() {
        val profiles = BcmTirePressure.catalogProfiles()
        assertEquals(listOf("225008", "225007", "225006", "225005"), profiles.map { it.command })
        assertTrue(profiles.all { it.header == "ATSH241" && it.unit == "kPa" })
    }
}
