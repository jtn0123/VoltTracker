package com.volttracker.obdpoc.signals

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticSignalsTest {
    @Test
    fun csvUsesFixedUnitsAgesAndBlanksWithoutExportingIdentityOrLocation() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val out = StringWriter()
            DiagnosticCsv(out).append(
                1_000L,
                JSONObject()
                    .put("batteryCoolantC", 26.5)
                    .put("batteryCoolantStaleMs", 400L)
                    .put("aux12vCurrentA", -1.5)
                    .put("aux12vStaleMs", 100L)
                    .put("controlModuleVoltage", 0.018)
                    .put("controlModuleVoltageStaleMs", 0L)
                    .put("egrCoolerTemp1C", 90)
                    .put("egrStaleMs", 120_001L)
                    .put("vin", "PRIVATE_IDENTITY")
                    .put("latitude", 1.234)
                    .put("raw", "PRIVATE_FRAMES"),
            )
            val lines = out.toString().trimEnd().lines()
            val header = lines[0].split(',')
            val row = lines[1].split(',')
            assertEquals(header.size, row.size)
            assertEquals("1970-01-01T00:00:01.000Z", row[0])
            assertEquals("26.5", row[header.indexOf("battery_coolant_c")])
            assertEquals("400", row[header.indexOf("battery_coolant_c_age_ms")])
            assertEquals("-1.5", row[header.indexOf("battery_12v_current_a")])
            assertEquals("", row[header.indexOf("controller_voltage_v")])
            assertEquals("", row[header.indexOf("egr_cooler_1_c")])
            assertFalse(out.toString().contains("PRIVATE"))
            assertFalse(header.any { it.contains("latitude") || it.contains("vin") })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun importedStatusTextCannotCreateASpreadsheetFormula() {
        val out = StringWriter()
        DiagnosticCsv(out).append(1L, JSONObject().put("engineStartReason", "=FORMULA(\"x,y\")"))
        assertTrue(out.toString().contains("\"'=FORMULA(\"\"x,y\"\")\""))
    }

    @Test
    fun chartReductionPreservesExtremesAndGapsWithinItsMemoryBound() {
        val builder = SignalRecordingBuilder(SignalSession(1, 0, 10_000), 0, 9_999_000L)
        repeat(10_000) { i ->
            val value =
                when (i) {
                    523 -> 999.0
                    7253 -> -999.0
                    else -> 30.0
                }
            builder.append(i * 1000L, JSONObject().put("batteryCoolantC", value).put("batteryCoolantStaleMs", 0))
        }
        val points = builder.build().series.getValue("batteryCoolantC")
        assertTrue(points.size <= 600)
        assertEquals(999.0, points.mapNotNull { it.value }.max(), 0.0)
        assertEquals(-999.0, points.mapNotNull { it.value }.min(), 0.0)
        assertEquals(0L, points.first().atMs)
        assertEquals(9_999_000L, points.last().atMs)
        assertEquals(points.sortedBy { it.atMs }, points)
    }

    @Test
    fun reductionNeverReconnectsSeparateRunsEvenWhenSeveralGapsShareABucket() {
        val builder = SignalRecordingBuilder(SignalSession(1, 0, 5), 0, 10_000L)
        builder.append(0, JSONObject().put("batteryCoolantC", 20))
        builder.append(1, JSONObject())
        builder.append(2, JSONObject().put("batteryCoolantC", 25))
        builder.append(3, JSONObject())
        builder.append(4, JSONObject().put("batteryCoolantC", 30))
        val points =
            builder
                .build()
                .series
                .getValue("batteryCoolantC")
                .filter { it.value != null }
        assertEquals(0, points.first().segment)
        assertEquals(2, points.last().segment)
    }

    @Test
    fun carriedValuesAreNotCountedAsNewSensorObservations() {
        val builder = SignalRecordingBuilder(SignalSession(1, 1_000, 3), 1_000, 3_000)
        builder.append(1_000, JSONObject().put("batteryCoolantC", 25).put("batteryCoolantStaleMs", 0))
        builder.append(2_000, JSONObject().put("batteryCoolantC", 25).put("batteryCoolantStaleMs", 1_000))
        builder.append(3_000, JSONObject().put("batteryCoolantC", "NaN").put("batteryCoolantStaleMs", 2_000))
        val points = builder.build().series.getValue("batteryCoolantC")
        assertEquals(1, points.count { it.value != null })
        assertTrue(points.last().value == null)
    }
}
