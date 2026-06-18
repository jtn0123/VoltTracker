package com.volttracker.obdpoc

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Tests the self-selecting, budget-bounded diagnostics digest. The whole point of the digest is that
 * the app decides what to ship so an external debugging tool isn't overflowed, so these pin the
 * selection priority (most recent + error sessions first), the budget cutoff, tail-trimming, and that
 * embedded text is redacted before it leaves the device.
 */
class DiagnosticsBundleTest {
    private lateinit var filesDir: File
    private lateinit var sessionDir: File
    private lateinit var appLogDir: File

    @Before
    @Throws(IOException::class)
    fun setUp() {
        filesDir = Files.createTempDirectory("diagbundle").toFile()
        sessionDir = File(filesDir, "obd-logs")
        appLogDir = File(filesDir, "app-log")
        assertTrue(sessionDir.mkdirs())
        assertTrue(appLogDir.mkdirs())
    }

    @After
    fun tearDown() {
        deleteRec(filesDir)
    }

    @Test
    fun manifestCatalogsEverySessionWithErrorCountsMostRecentFirst() {
        writeSession(1_000L, "obd", body(800, errorLines = 0))
        writeSession(2_000L, "scan", body(800, errorLines = 3))
        writeSession(3_000L, "obd", body(800, errorLines = 0))

        val digest = DiagnosticsBundle.build(filesDir, DiagnosticsBundle.DEFAULT_BUDGET_BYTES, NOW)
        val manifest = manifestOf(digest)

        assertEquals("every session should be catalogued", 3, manifest.length())
        // The most recent (ts/mtime 3000) leads the inclusion order.
        assertEquals("session-3000-obd.jsonl", manifest.getJSONObject(0).getString("name"))
        val byName =
            (0 until manifest.length()).associate {
                val o = manifest.getJSONObject(it)
                o.getString("name") to o
            }
        assertEquals(3, byName.getValue("session-2000-scan.jsonl").getInt("errorLines"))
        assertEquals(0, byName.getValue("session-1000-obd.jsonl").getInt("errorLines"))
    }

    @Test
    fun errorSessionsBeatCleanOnesWhenBudgetForcesAChoice() {
        // A (oldest, clean), B (errors), C (clean), D (newest, clean). Body ~2000 bytes each; the
        // budget only fits the most-recent (D) plus one more — the error session B should win over the
        // newer-but-clean C.
        writeSession(1_000L, "obd", body(2_000, errorLines = 0)) // A
        writeSession(2_000L, "scan", body(2_000, errorLines = 1)) // B
        writeSession(3_000L, "obd", body(2_000, errorLines = 0)) // C
        writeSession(4_000L, "obd", body(2_000, errorLines = 0)) // D

        val digest = DiagnosticsBundle.build(filesDir, 6_400, NOW)
        val byName = manifestByName(digest)

        assertTrue("most recent session included", byName.getValue("session-4000-obd.jsonl").getBoolean("included"))
        assertTrue("error session included", byName.getValue("session-2000-scan.jsonl").getBoolean("included"))
        assertFalse(
            "newer-but-clean session dropped for the error one",
            byName.getValue("session-3000-obd.jsonl").getBoolean("included"),
        )
        assertFalse("oldest clean session dropped", byName.getValue("session-1000-obd.jsonl").getBoolean("included"))

        assertTrue("digest embeds the most-recent body", digest.contains("SESSION: session-4000-obd.jsonl"))
        assertTrue("digest embeds the error body", digest.contains("SESSION: session-2000-scan.jsonl"))
        assertFalse("digest omits the dropped body", digest.contains("SESSION: session-3000-obd.jsonl"))
        assertTrue("digest reports the omitted count", digest.contains("===== OMITTED ====="))
    }

    @Test
    fun oversizeSessionBodyKeepsTheTailAndIsMarkedTruncated() {
        val sb = StringBuilder()
        for (i in 1..300) {
            sb.append("LINE-").append(String.format(Locale.US, "%04d", i)).append("-padding-padding\n")
        }
        writeSession(5_000L, "obd", sb.toString())
        val originalBytes = File(sessionDir, "session-5000-obd.jsonl").length()
        assertTrue("fixture should exceed the test budget", originalBytes > 3_000L)

        val digest = DiagnosticsBundle.build(filesDir, 3_000, NOW)

        assertTrue("the tail (latest lines) survives", digest.contains("LINE-0300"))
        assertFalse("the head (oldest lines) is trimmed", digest.contains("LINE-0001"))
        assertTrue(
            "manifest marks the body truncated",
            manifestByName(digest).getValue("session-5000-obd.jsonl").getBoolean("truncated"),
        )
    }

    @Test
    fun embeddedTextIsRedacted() {
        writeSession(
            6_000L,
            "obd",
            "{\"ts\":1,\"type\":\"command\",\"adapter\":\"AA:BB:CC:DD:EE:FF\"," +
                "\"vin\":\"1G1RD6E45CU" + "112233\",\"lat\":34.052345,\"command\":\"010C\"}\n",
        )

        val digest = DiagnosticsBundle.build(filesDir, DiagnosticsBundle.DEFAULT_BUDGET_BYTES, NOW)

        assertFalse(digest.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(digest.contains("1G1RD6E45CU" + "112233"))
        assertFalse(digest.contains("34.052345"))
        assertTrue(digest.contains("[bluetooth-address-redacted]"))
        assertTrue(digest.contains("[vin-redacted]"))
        assertTrue(digest.contains("[coordinate-redacted]"))
        // Non-sensitive content stays intact so the digest is still useful.
        assertTrue(digest.contains("\"command\":\"010C\""))
    }

    @Test
    fun summaryRollupAndAppLogAreIncluded() {
        writeSession(7_000L, "obd", body(400, errorLines = 0))
        File(sessionDir, "sessions-summary.jsonl").writeText("{\"outcome\":\"success\",\"sampleCount\":42}\n")
        File(appLogDir, "app.log").writeText("2026-06-14T00:00:00.000 I VoltTracker: boot ok\n")

        val digest = DiagnosticsBundle.build(filesDir, DiagnosticsBundle.DEFAULT_BUDGET_BYTES, NOW)

        assertTrue(digest.contains("SESSION SUMMARY ROLLUP"))
        assertTrue(digest.contains("\"sampleCount\":42"))
        assertTrue(digest.contains("APP LOG TAIL"))
        assertTrue(digest.contains("boot ok"))
    }

    @Test
    fun smallSessionThatFitsInFullIsKeptEvenBelowTheMinSliceFloor() {
        // Newest clean session + a tiny (<256 byte) error session. The error session is well under
        // MIN_SESSION_BYTES but fits in full, so it must still be embedded -- otherwise short error
        // sessions are permanently ineligible and the "errors before clean ones" rule never applies.
        writeSession(1_000L, "scan", "{\"ts\":1,\"type\":\"error\",\"payload\":{}}\n") // ~37 bytes
        writeSession(2_000L, "obd", body(1_000, errorLines = 0))

        val digest = DiagnosticsBundle.build(filesDir, DiagnosticsBundle.DEFAULT_BUDGET_BYTES, NOW)
        val byName = manifestByName(digest)

        assertTrue(
            "a tiny error session that fits in full should be included",
            byName.getValue("session-1000-scan.jsonl").getBoolean("included"),
        )
        assertTrue("its body is embedded", digest.contains("SESSION: session-1000-scan.jsonl"))
    }

    @Test
    fun digestNeverExceedsBudgetEvenWithLargeFixedSectionsAndTightBudget() {
        // A summary and app log that, appended at their full caps, would dwarf a tight budget. The
        // digest must enforce the real remaining budget on every section, not blindly append the caps.
        writeSession(9_000L, "obd", body(4_000, errorLines = 0))
        File(sessionDir, "sessions-summary.jsonl").writeText("x".repeat(40_000) + "\n")
        File(appLogDir, "app.log").writeText("y".repeat(40_000) + "\n")

        val budget = 4_000
        val digest = DiagnosticsBundle.build(filesDir, budget, NOW)

        assertTrue(
            "digest length ${digest.length} must stay within the advertised budget $budget",
            digest.length <= budget,
        )
        // The manifest is the irreducible catalog and still ships, so nothing is silently hidden.
        assertTrue("manifest still present under budget pressure", digest.contains("MANIFEST"))
    }

    @Test
    fun manifestInclusionFlagsStayInLockstepWithEmittedBodies() {
        // Under budget pressure the MANIFEST's included flag must match exactly which session bodies are
        // embedded: no "included":true entry may be silently dropped from the output, and vice versa.
        for (i in 1..6) {
            writeSession(1_000L * i, "obd", body(2_000, errorLines = if (i == 2) 1 else 0))
        }

        val digest = DiagnosticsBundle.build(filesDir, 8_000, NOW)
        val manifest = manifestOf(digest)

        var omitted = 0
        for (idx in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(idx)
            val name = entry.getString("name")
            val included = entry.getBoolean("included")
            val bodyEmitted = digest.contains("SESSION: $name")
            assertEquals("manifest 'included' must match the emitted body for $name", included, bodyEmitted)
            if (!included) omitted++
        }
        assertTrue("this budget should force at least one omission", omitted > 0)
        assertTrue("digest reports the omitted sessions", digest.contains("===== OMITTED ====="))
        assertTrue("digest stays within the advertised budget", digest.length <= 8_000)
    }

    @Test
    fun emptyDeviceProducesAMinimalDigestWithoutCrashing() {
        val digest = DiagnosticsBundle.build(filesDir, DiagnosticsBundle.DEFAULT_BUDGET_BYTES, NOW)

        assertTrue(digest.contains("VoltTracker diagnostics digest"))
        assertTrue(digest.contains("===== END"))
        assertEquals("manifest is an empty array when there are no sessions", 0, manifestOf(digest).length())
    }

    // ---- helpers ------------------------------------------------------------

    private fun writeSession(
        ts: Long,
        mode: String,
        content: String,
    ) {
        val file = File(sessionDir, "session-$ts-$mode.jsonl")
        file.writeText(content)
        // Pin mtime to the embedded ts so recency ordering is deterministic.
        assertTrue(file.setLastModified(ts))
    }

    private fun body(
        targetBytes: Int,
        errorLines: Int,
    ): String {
        val sb = StringBuilder()
        repeat(errorLines) { sb.append("{\"ts\":1,\"type\":\"error\",\"payload\":{}}\n") }
        var n = 0
        while (sb.length < targetBytes) {
            sb.append("{\"ts\":1,\"type\":\"telemetry\",\"payload\":{\"seq\":").append(n++).append("}}\n")
        }
        return sb.toString()
    }

    private fun manifestOf(digest: String): JSONArray {
        val marker = "=====\n"
        val start = digest.indexOf("MANIFEST")
        val jsonStart = digest.indexOf('[', digest.indexOf(marker, start))
        val jsonEnd = digest.indexOf("]\n", jsonStart)
        return JSONArray(digest.substring(jsonStart, jsonEnd + 1))
    }

    private fun manifestByName(digest: String): Map<String, org.json.JSONObject> {
        val arr = manifestOf(digest)
        return (0 until arr.length()).associate {
            val o = arr.getJSONObject(it)
            o.getString("name") to o
        }
    }

    companion object {
        private const val NOW = 1_700_000_000_000L

        private fun deleteRec(file: File?) {
            if (file == null || !file.exists()) return
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteRec(it) }
            }
            file.delete()
        }
    }
}
