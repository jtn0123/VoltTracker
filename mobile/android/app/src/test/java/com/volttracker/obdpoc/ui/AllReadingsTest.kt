package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.diag.RawReading
import com.volttracker.obdpoc.ui.diag.ageText
import com.volttracker.obdpoc.ui.diag.matching
import com.volttracker.obdpoc.ui.diag.rawReadings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllReadingsTest {
    private val sample =
        JSONObject()
            .put("packVoltage", 359.25)
            .put("socPct", 62.0)
            .put("speedKph", 48)
            .put("batteryTempC", 21)
            .put("chargeAddedKwh", 1.5)
            .put("coolantC", 88)
            .put("packCurrentA", -42.5)
            .put("tirePressureFlKpa", 262)
            .put("motorATempC", 48)
            .put("gearState", "D")
            .put("acOn", true)
            .put("aux12vVoltage", 12.6)
            .put("aux12vVoltageStaleMs", 18_000)
            .put("source", "obd")
            .put("empty", "  ")
            .put("nested", JSONObject())
            .put("list", JSONArray())
            .put("missing", JSONObject.NULL)

    private val readings = rawReadings(sample, setOf("source"))

    private fun value(label: String) = readings.first { it.label == label }.value

    @Test
    fun keysBecomeNamesWithTheirUnits() {
        assertEquals("359.25 V", value("Pack voltage"))
        assertEquals("62 %", value("SOC"))
        assertEquals("48 km/h", value("Speed"))
        assertEquals("21 °C", value("Battery temp"))
        assertEquals("1.5 kWh", value("Charge added"))
        assertEquals("88 °C", value("Coolant"))
        assertEquals("-42.5 A", value("Pack current"))
        assertEquals("262 kPa", value("Tire pressure front left"))
        assertEquals("48 °C", value("Motor A temp"))
        assertEquals("12.6 V", value("12 V voltage"))
        assertEquals("D", value("Gear state"))
        assertEquals("yes", value("A/C on"))
    }

    @Test
    fun bookkeepingAgesEmptiesAndNestedValuesAreLeftOut() {
        val keys = readings.map { it.key }
        assertEquals(
            listOf(
                "aux12vVoltage",
                "acOn",
                "batteryTempC",
                "chargeAddedKwh",
                "coolantC",
                "gearState",
                "motorATempC",
                "packCurrentA",
                "packVoltage",
                "socPct",
                "speedKph",
                "tirePressureFlKpa",
            ),
            keys,
        )
    }

    @Test
    fun staleReadingsSayHowOld() {
        assertEquals("18 s old", readings.first { it.key == "aux12vVoltage" }.ageText())
        assertNull(readings.first { it.key == "packVoltage" }.ageText())
        assertEquals("3 min old", RawReading("k", "K", "1", ageMs = 200_000L).ageText())
        assertNull(RawReading("k", "K", "1", ageMs = 4_000L).ageText())
    }

    @Test
    fun theFilterMatchesNameOrKey() {
        assertEquals(listOf("packCurrentA", "packVoltage"), readings.matching("pack").map { it.key })
        assertEquals(listOf("aux12vVoltage"), readings.matching("AUX12").map { it.key })
        assertEquals(listOf("aux12vVoltage"), readings.matching("12 v").map { it.key })
        assertEquals(readings, readings.matching("  "))
    }
}
