package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Round-trip and retention tests for {@link SessionSummaryStore}. The store is the dashboard's
 * source of truth for the "last connected" badge — a regression here means the UI lies about
 * adapter health, so the tests pin the on-disk shape, the truncation cutoff, and the newest-first
 * read order.
 */
public class SessionSummaryStoreTest {

    private File tmpDir;
    private File summaryFile;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("ssstore").toFile();
        // The store builds its own path under filesDir/obd-logs/; mirror that here.
        summaryFile = new File(new File(tmpDir, "obd-logs"), "sessions-summary.jsonl");
    }

    @After
    public void tearDown() {
        deleteRec(tmpDir);
        SessionSummaryStore.resetForTests();
    }

    @Test
    public void roundTripPersistsStartContextOntoEndRow() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);

        store.recordStart(1_000L, "Adapter X", "AA:BB:CC");
        store.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 42, null);

        List<SessionSummary> recent = store.getRecent(10);
        assertEquals(1, recent.size());
        SessionSummary s = recent.get(0);
        assertEquals(1_000L, s.startMs);
        assertEquals(2_000L, s.endMs);
        assertEquals(SessionSummary.OUTCOME_SUCCESS, s.outcome);
        assertEquals(42, s.sampleCount);
        assertEquals("Adapter X", s.adapter);
        assertEquals("AA:BB:CC", s.adapterAddress);
        assertNull("success outcome should leave failureClass null", s.failureClass);
    }

    @Test
    public void recordEndPersistsFailureClassWhenSupplied() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);

        store.recordStart(1_000L, "Adapter", "AA:BB:CC");
        store.recordEnd(1_500L, SessionSummary.OUTCOME_FAILED, 0, "INSTANT_DROP");

        SessionSummary s = store.getRecent(1).get(0);
        assertEquals(SessionSummary.OUTCOME_FAILED, s.outcome);
        assertEquals("INSTANT_DROP", s.failureClass);
    }

    @Test
    public void recordEndWithoutPriorRecordStartUsesZeroStart() {
        // The pending context defaults to zero; a stray recordEnd shouldn't lift the previous
        // session's start back into this row.
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);

        store.recordEnd(5_000L, SessionSummary.OUTCOME_ABORTED, 0, null);

        SessionSummary s = store.getRecent(1).get(0);
        assertEquals(0L, s.startMs);
        assertEquals(5_000L, s.endMs);
        assertEquals("", s.adapter);
        assertEquals("", s.adapterAddress);
    }

    @Test
    public void recordEndClearsPendingContextSoNextSessionDoesntInherit() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);

        store.recordStart(1_000L, "Adapter A", "AA:11");
        store.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 1, null);

        // A subsequent recordEnd with no recordStart must NOT inherit "Adapter A".
        store.recordEnd(3_000L, SessionSummary.OUTCOME_ABORTED, 0, null);

        List<SessionSummary> recent = store.getRecent(10);
        assertEquals(2, recent.size());
        // getRecent is newest-first.
        assertEquals(3_000L, recent.get(0).endMs);
        assertEquals(
                "second session must have empty adapter context, not inherit the first session's",
                "",
                recent.get(0).adapter);
        assertEquals("Adapter A", recent.get(1).adapter);
    }

    @Test
    public void getRecentReturnsNewestFirst() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        for (int i = 0; i < 5; i++) {
            store.recordStart(1_000L + i, "Adapter", "AA");
            store.recordEnd(2_000L + i, SessionSummary.OUTCOME_SUCCESS, i, null);
        }

        List<SessionSummary> recent = store.getRecent(3);
        assertEquals(3, recent.size());
        // Most recent (sampleCount=4) first, then 3, then 2.
        assertEquals(4, recent.get(0).sampleCount);
        assertEquals(3, recent.get(1).sampleCount);
        assertEquals(2, recent.get(2).sampleCount);
    }

    @Test
    public void getRecentZeroOrNegativeReturnsEmpty() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        store.recordStart(1L, "A", "AA");
        store.recordEnd(2L, SessionSummary.OUTCOME_SUCCESS, 1, null);

        assertTrue(store.getRecent(0).isEmpty());
        assertTrue(store.getRecent(-1).isEmpty());
    }

    @Test
    public void truncationKeepsTheLastHundredOnOverflow() throws IOException {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        // Write 150 sessions, expect only the last 100 to remain.
        for (int i = 0; i < 150; i++) {
            store.recordStart(1_000L + i, "Adapter", "AA");
            store.recordEnd(2_000L + i, SessionSummary.OUTCOME_SUCCESS, i, null);
        }

        // On disk: exactly 100 lines.
        List<String> lines = readAllLines(summaryFile);
        assertEquals(SessionSummaryStore.MAX_SESSIONS, lines.size());

        // The oldest 50 are gone; the newest 100 (sampleCount 50..149) survived.
        List<SessionSummary> recent = store.getRecent(SessionSummaryStore.MAX_SESSIONS);
        assertEquals(SessionSummaryStore.MAX_SESSIONS, recent.size());
        // Newest first → sampleCount 149 at index 0.
        assertEquals(149, recent.get(0).sampleCount);
        assertEquals(50, recent.get(recent.size() - 1).sampleCount);
    }

    @Test
    public void persistsAcrossNewInstancesPointedAtSameFile() {
        SessionSummaryStore first = new SessionSummaryStore(summaryFile);
        first.recordStart(1_000L, "Adapter", "AA");
        first.recordEnd(2_000L, SessionSummary.OUTCOME_SUCCESS, 7, null);

        SessionSummaryStore second = new SessionSummaryStore(summaryFile);
        List<SessionSummary> recent = second.getRecent(10);
        assertEquals(1, recent.size());
        assertEquals(7, recent.get(0).sampleCount);
    }

    @Test
    public void getInstanceIsSingletonPerProcess() {
        SessionSummaryStore a = SessionSummaryStore.getInstance(tmpDir);
        SessionSummaryStore b = SessionSummaryStore.getInstance(tmpDir);
        assertNotNull(a);
        assertTrue("getInstance must return the same store on subsequent calls", a == b);
    }

    @Test
    public void getRecentAsJsonArrayHonoursNewestFirstAndLimit() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        for (int i = 0; i < 3; i++) {
            store.recordStart(i, "A", "AA");
            store.recordEnd(i + 100, SessionSummary.OUTCOME_SUCCESS, i, null);
        }

        String json = store.getRecentAsJsonArray(2);
        // Cheapest pin: array brackets + the newest session's sampleCount=2 must appear before
        // the older sampleCount=1 (because newest first).
        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        int idx2 = json.indexOf("\"sampleCount\":2");
        int idx1 = json.indexOf("\"sampleCount\":1");
        assertTrue("sampleCount 2 should appear in JSON: " + json, idx2 >= 0);
        assertTrue("sampleCount 1 should appear in JSON: " + json, idx1 >= 0);
        assertTrue("newest-first ordering", idx2 < idx1);
    }

    // ---- helpers ------------------------------------------------------------

    private static List<String> readAllLines(File file) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        // Best-effort; tests cleanup.
        boolean ignored = f.delete();
    }
}
