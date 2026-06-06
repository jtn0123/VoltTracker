package com.volttracker.obdpoc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCodeReportTest {
    @Test
    fun toJsonKeepsDashboardFieldNamesStable() {
        val report =
            DiagnosticCodeReport(
                42L,
                " P25A2 ",
                " stored ",
                "Stored",
                " powertrain ",
                "ECM",
                "7E0",
                1000L,
                2000L,
                3L,
                null,
                " 43 01 25 A2 ",
            )

        val payload = report.toJson()

        assertEquals(42L, payload.optLong("id"))
        assertEquals("P25A2", payload.optString("dtc"))
        assertEquals("stored", payload.optString("status"))
        assertEquals("Stored", payload.optString("statusLabel"))
        assertEquals("powertrain", payload.optString("moduleKey"))
        assertEquals("ECM", payload.optString("moduleName"))
        assertEquals("7E0", payload.optString("header"))
        assertEquals(1000L, payload.optLong("firstSeenMs"))
        assertEquals(2000L, payload.optLong("lastSeenMs"))
        assertEquals(3L, payload.optLong("seenCount"))
        assertTrue(payload.isNull("lastSessionId"))
        assertEquals("43 01 25 A2", payload.optString("rawResponse"))
    }
}
