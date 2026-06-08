package com.volttracker.obdpoc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Files

/**
 * Round-trip and retention tests for [SessionSummaryStore]. The store is the dashboard's source of
 * truth for the "last connected" badge — a regression here means the UI lies about adapter health,
 * so the tests pin the on-disk shape, the truncation cutoff, and the newest-first read order.
 */
class SessionSummaryStoreTest {
    private lateinit var tmpDir: File
    private lateinit var summaryFile: File

    @Before
    @Throws(IOException::class)
    fun setUp() {
        tmpDir = Files.createTempDirectory("ssstore").toFile()
        // The store builds its own path under filesDir/obd-logs/; mirror that here.
        summaryFile = File(File(tmpDir, "obd-logs"), "sessions-summary.jsonl")
    }

    @After
    fun tearDown() {
        deleteRec(tmpDir)
        SessionSummaryStore.resetForTests()
    }

    @Test
    fun roundTripPersistsStartContextOntoEndRow() {
        val store = SessionSummaryStore(summaryFile)

        store.recordStart(1_000L, "Adapter X", "AA:BB:CC")
        store.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 42, null)

        val recent = store.getRecent(10)
        assertEquals(1, recent.size)
        val s = recent[0]
        assertEquals(1_000L, s.startMs)
        assertEquals(2_000L, s.endMs)
        assertEquals(SessionSummary.OUTCOME_SUCCESS, s.outcome)
        assertEquals(42, s.sampleCount)
        assertEquals("Adapter X", s.adapter)
        assertEquals("AA:BB:CC", s.adapterAddress)
        assertNull("success outcome should leave failureClass null", s.failureClass)
    }

    @Test
    fun recordEndPersistsFailureClassWhenSupplied() {
        val store = SessionSummaryStore(summaryFile)

        store.recordStart(1_000L, "Adapter", "AA:BB:CC")
        store.recordEnd(1_500L, SessionSummary.OUTCOME_FAILED, 0, "INSTANT_DROP")

        val s = store.getRecent(1)[0]
        assertEquals(SessionSummary.OUTCOME_FAILED, s.outcome)
        assertEquals("INSTANT_DROP", s.failureClass)
    }

    @Test
    fun recordEndWithoutPriorRecordStartUsesZeroStart() {
        // The pending context defaults to zero; a stray recordEnd shouldn't lift the previous
        // session's start back into this row.
        val store = SessionSummaryStore(summaryFile)

        store.recordEnd(5_000L, SessionSummary.OUTCOME_ABORTED, 0, null)

        val s = store.getRecent(1)[0]
        assertEquals(0L, s.startMs)
        assertEquals(5_000L, s.endMs)
        assertEquals("", s.adapter)
        assertEquals("", s.adapterAddress)
    }

    @Test
    fun recordEndClearsPendingContextSoNextSessionDoesntInherit() {
        val store = SessionSummaryStore(summaryFile)

        store.recordStart(1_000L, "Adapter A", "AA:11")
        store.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 1, null)

        // A subsequent recordEnd with no recordStart must NOT inherit "Adapter A".
        store.recordEnd(3_000L, SessionSummary.OUTCOME_ABORTED, 0, null)

        val recent = store.getRecent(10)
        assertEquals(2, recent.size)
        // getRecent is newest-first.
        assertEquals(3_000L, recent[0].endMs)
        assertEquals(
            "second session must have empty adapter context, not inherit the first session's",
            "",
            recent[0].adapter,
        )
        assertEquals("Adapter A", recent[1].adapter)
    }

    @Test
    fun getRecentReturnsNewestFirst() {
        val store = SessionSummaryStore(summaryFile)
        for (i in 0 until 5) {
            store.recordStart(1_000L + i, "Adapter", "AA")
            store.recordEnd(2_000L + i, SessionSummary.OUTCOME_SUCCESS, i, null)
        }

        val recent = store.getRecent(3)
        assertEquals(3, recent.size)
        // Most recent (sampleCount=4) first, then 3, then 2.
        assertEquals(4, recent[0].sampleCount)
        assertEquals(3, recent[1].sampleCount)
        assertEquals(2, recent[2].sampleCount)
    }

    @Test
    fun getRecentZeroOrNegativeReturnsEmpty() {
        val store = SessionSummaryStore(summaryFile)
        store.recordStart(1L, "A", "AA")
        store.recordEnd(2L, SessionSummary.OUTCOME_SUCCESS, 1, null)

        assertTrue(store.getRecent(0).isEmpty())
        assertTrue(store.getRecent(-1).isEmpty())
    }

    @Test
    @Throws(IOException::class)
    fun truncationKeepsTheLastHundredOnOverflow() {
        val store = SessionSummaryStore(summaryFile)
        // Write 150 sessions, expect only the last 100 to remain.
        for (i in 0 until 150) {
            store.recordStart(1_000L + i, "Adapter", "AA")
            store.recordEnd(2_000L + i, SessionSummary.OUTCOME_SUCCESS, i, null)
        }

        // On disk: exactly 100 lines.
        val lines = readAllLines(summaryFile)
        assertEquals(SessionSummaryStore.MAX_SESSIONS, lines.size)

        // The oldest 50 are gone; the newest 100 (sampleCount 50..149) survived.
        val recent = store.getRecent(SessionSummaryStore.MAX_SESSIONS)
        assertEquals(SessionSummaryStore.MAX_SESSIONS, recent.size)
        // Newest first → sampleCount 149 at index 0.
        assertEquals(149, recent[0].sampleCount)
        assertEquals(50, recent[recent.size - 1].sampleCount)
    }

    @Test
    fun persistsAcrossNewInstancesPointedAtSameFile() {
        val first = SessionSummaryStore(summaryFile)
        first.recordStart(1_000L, "Adapter", "AA")
        first.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 7, null)

        val second = SessionSummaryStore(summaryFile)
        val recent = second.getRecent(10)
        assertEquals(1, recent.size)
        assertEquals(7, recent[0].sampleCount)
    }

    @Test
    fun getInstanceIsSingletonPerProcess() {
        val a = SessionSummaryStore.getInstance(tmpDir)
        val b = SessionSummaryStore.getInstance(tmpDir)
        assertNotNull(a)
        assertTrue("getInstance must return the same store on subsequent calls", a === b)
    }

    @Test
    fun getRecentAsJsonArrayHonoursNewestFirstAndLimit() {
        val store = SessionSummaryStore(summaryFile)
        for (i in 0 until 3) {
            store.recordStart(i.toLong(), "A", "AA")
            store.recordEnd((i + 100).toLong(), SessionSummary.OUTCOME_SUCCESS, i, null)
        }

        val json = store.getRecentAsJsonArray(2)
        // Cheapest pin: array brackets + the newest session's sampleCount=2 must appear before
        // the older sampleCount=1 (because newest first).
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        val idx2 = json.indexOf("\"sampleCount\":2")
        val idx1 = json.indexOf("\"sampleCount\":1")
        assertTrue("sampleCount 2 should appear in JSON: $json", idx2 >= 0)
        assertTrue("sampleCount 1 should appear in JSON: $json", idx1 >= 0)
        assertTrue("newest-first ordering", idx2 < idx1)
    }

    // ---- helpers ------------------------------------------------------------

    private companion object {
        @Throws(IOException::class)
        private fun readAllLines(file: File): List<String> {
            val out = ArrayList<String>()
            BufferedReader(FileReader(file)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) {
                        out.add(line)
                    }
                    line = r.readLine()
                }
            }
            return out
        }

        private fun deleteRec(f: File?) {
            if (f == null) return
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids != null) {
                    for (k in kids) deleteRec(k)
                }
            }
            // Best-effort; tests cleanup.
            @Suppress("UNUSED_VARIABLE")
            val ignored = f.delete()
        }
    }
}
