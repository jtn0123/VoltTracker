package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.diag.DtcCatalog
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.diag.savedCodes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The build-time DTC table, the family fallback, and the saved codes read from the store. */
class DtcCatalogTest {
    private val catalog =
        DtcCatalog.parse(
            "P0420\tCatalyst system efficiency below threshold (Bank 1)\twarning\t1.4L engine\n" +
                "P0AA6\tHybrid battery isolation fault\tcritical\tHV battery\n" +
                "P1000\t\tinfo\t\n" +
                "\n\tno code\twarning\n",
        )

    @Test
    fun parseReadsEachColumn() {
        assertEquals(3, catalog.size)
        val p0420 = catalog.describe("p0420 ", firstSeenMs = 1L, lastSeenMs = 2L, seenCount = 3)
        assertEquals(
            DtcCode(
                code = "P0420",
                description = "Catalyst system efficiency below threshold (Bank 1)",
                category = "1.4L engine",
                severity = DtcSeverity.WARNING,
                firstSeenMs = 1L,
                lastSeenMs = 2L,
                seenCount = 3,
            ),
            p0420,
        )
        assertEquals(DtcSeverity.ALERT, catalog.describe("P0AA6").severity)
        val bare = catalog.describe("P1000")
        assertEquals(DtcSeverity.INFO, bare.severity)
        assertNull(bare.description)
        assertNull(bare.category)
    }

    @Test
    fun anUnlistedCodeFallsBackToItsFamily() {
        assertEquals(DtcSeverity.ALERT, DtcCatalog.EMPTY.describe("C0035").severity)
        assertEquals(DtcSeverity.WARNING, DtcCatalog.EMPTY.describe("U0100").severity)
        assertEquals(DtcSeverity.INFO, DtcCatalog.familySeverity("X"))
        assertEquals("Hybrid battery / cell", DtcCatalog.familyName("P0B3D"))
        assertEquals("Manufacturer-specific powertrain (GM / Volt)", DtcCatalog.familyName("P1F00"))
        assertEquals("Generic powertrain (extended SAE)", DtcCatalog.familyName("P2A00"))
        assertEquals("Manufacturer-specific powertrain (extended)", DtcCatalog.familyName("P3100"))
        assertEquals("Body / restraints code", DtcCatalog.familyName("B0001"))
        assertEquals("Chassis / ABS / brakes code", DtcCatalog.familyName("C0001"))
        assertEquals("Network / communication bus code", DtcCatalog.familyName("U0001"))
        assertNull(DtcCatalog.familyName("P0"))
        assertNull(DtcCatalog.familyName("Z0001"))
        assertNull(DtcCatalog.familyName("P0F00"))
    }

    @Test
    fun theGeneratedTableNamesTheVoltsCodes() {
        val table = DtcCatalog.parse(File("src/main/assets/${DtcCatalog.ASSET_PATH}").readText())
        assertTrue("table has ${table.size} codes", table.size > 1000)
        val p0420 = table.describe("P0420")
        assertTrue(p0420.description.orEmpty().startsWith("Catalyst system efficiency"))
        assertEquals("1.4L engine", p0420.category)
    }

    @Test
    fun savedCodesAreListedOnceAtTheirNewest() {
        fun row(
            dtc: String,
            last: Long,
            status: String = "stored",
        ) = JSONObject()
            .put("dtc", dtc)
            .put("status", status)
            .put("firstSeenMs", 10L)
            .put("lastSeenMs", last)
            .put("seenCount", 2)
        val summary =
            JSONObject().put(
                "latestDiagnosticCodes",
                JSONArray()
                    .put(row("P0420", 300L))
                    .put(row("p0011", 500L, "pending"))
                    .put(row("P0420", 100L, "permanent"))
                    .put(row(" ", 900L))
                    .put(JSONObject().put("dtc", "P0300").put("status", "").put("seenCount", 0))
                    .put("not an object"),
            )
        val codes = savedCodes(summary, catalog)
        assertEquals(listOf("P0011", "P0420", "P0300"), codes.map { it.code })
        assertEquals("pending", codes[0].status)
        assertEquals("stored", codes[1].status)
        assertEquals("1.4L engine", codes[1].category)
        assertEquals("stored", codes[2].status)
        assertEquals(1, codes[2].seenCount)
        assertEquals(emptyList<DtcCode>(), savedCodes(JSONObject(), catalog))
    }

    @Test
    fun aCodeTakesTheStrongestStatusItsNewestSessionReported() {
        fun row(
            status: String,
            last: Long,
            session: Long,
        ) = JSONObject()
            .put("dtc", "P0128")
            .put("status", status)
            .put("firstSeenMs", 100L)
            .put("lastSeenMs", last)
            .put("seenCount", 1)
            .put("lastSessionId", session)

        // One scan reads Mode 03, then 0A, then the freeze frame: the newest row is the freeze frame's.
        val oneScan =
            JSONArray()
                .put(row("freeze-frame", 340L, 7L))
                .put(row("permanent", 220L, 7L))
                .put(row("stored", 100L, 7L))
        val code = savedCodes(JSONObject().put("latestDiagnosticCodes", oneScan), catalog).single()
        assertEquals("stored", code.status)
        assertEquals("still dated by its newest report", 340L, code.lastSeenMs)

        // Cleared, then rescanned (session 9): only Mode 0A still reports it.
        val afterClear =
            JSONArray()
                .put(row("permanent", 900L, 9L))
                .put(row("freeze-frame", 340L, 7L))
                .put(row("stored", 100L, 7L))
        assertEquals(
            "permanent",
            savedCodes(JSONObject().put("latestDiagnosticCodes", afterClear), catalog).single().status,
        )

        // A pending code the car hasn't confirmed yet stays pending; a pending and permanent one is permanent.
        val pending = JSONArray().put(row("pending", 500L, 3L)).put(row("freeze-frame", 520L, 3L))
        assertEquals("pending", savedCodes(JSONObject().put("latestDiagnosticCodes", pending), catalog).single().status)
        val both = JSONArray().put(row("pending", 500L, 3L)).put(row("permanent", 480L, 3L))
        assertEquals("permanent", savedCodes(JSONObject().put("latestDiagnosticCodes", both), catalog).single().status)
    }
}
