package com.volttracker.obdpoc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Append-only one-summary-per-line rollup at {@code files/obd-logs/sessions-summary.jsonl}. The
 * per-session {@code .jsonl} is the full record; this file is the index used to render the "last
 * connected" badge and adapter-health pill without parsing every detail log.
 *
 * <p>{@link #recordStart} stores the open-session context in memory so the matching {@link
 * #recordEnd} call can stamp a {@link SessionSummary} with both timestamps + adapter identity. The
 * store keeps at most {@link #MAX_SESSIONS} lines on disk; on overflow we read, tail-slice, and
 * rewrite — this happens once per session-end, so the O(n) cost is fine.
 *
 * <p>One singleton per app process. Reads of {@code getRecent} and writes both serialize on the
 * same lock so the JS bridge thread can't see a half-rewritten file.
 */
final class SessionSummaryStore {

    /** Most lines we retain. Beyond this we drop from the head. */
    static final int MAX_SESSIONS = 100;

    private static final String FILE_NAME = "sessions-summary.jsonl";

    private static volatile SessionSummaryStore singleton;

    private final File file;
    private final Object lock = new Object();

    // In-flight session context — populated by recordStart, consumed and cleared by recordEnd.
    private long pendingStartMs;
    private String pendingAdapter = "";
    private String pendingAddress = "";

    /**
     * Returns the process-wide store rooted at {@code filesDir/obd-logs/}. The directory matches
     * the per-session JSONL location used by {@link ObdService}, so the diagnostics zip can grab
     * both with one cache-dir sweep.
     */
    static SessionSummaryStore getInstance(File filesDir) {
        SessionSummaryStore existing = singleton;
        if (existing != null) {
            return existing;
        }
        synchronized (SessionSummaryStore.class) {
            if (singleton == null) {
                File dir = new File(filesDir, "obd-logs");
                singleton = new SessionSummaryStore(new File(dir, FILE_NAME));
            }
            return singleton;
        }
    }

    /** Test seam: drops the cached instance so the next {@code getInstance} re-reads disk. */
    static void resetForTests() {
        synchronized (SessionSummaryStore.class) {
            singleton = null;
        }
    }

    SessionSummaryStore(File file) {
        this.file = file;
    }

    /**
     * Records the open-session context so the eventual {@link #recordEnd} call can stamp a full
     * summary. Idempotent within a single session — a second call replaces the first. Side-effect
     * free on disk.
     */
    void recordStart(long startMs, String adapter, String address) {
        synchronized (lock) {
            pendingStartMs = startMs;
            pendingAdapter = adapter == null ? "" : adapter;
            pendingAddress = address == null ? "" : address;
        }
    }

    /**
     * Appends one {@link SessionSummary} line built from the pending context + the close-time
     * fields. If retention is exceeded, rewrites the file with the most recent {@link
     * #MAX_SESSIONS} lines.
     */
    void recordEnd(long endMs, String outcome, int sampleCount, String failureClass) {
        SessionSummary summary;
        synchronized (lock) {
            summary =
                    new SessionSummary(
                            pendingStartMs,
                            endMs,
                            outcome,
                            sampleCount,
                            pendingAdapter,
                            pendingAddress,
                            failureClass);
            // Clear so a stray second recordEnd doesn't reuse stale start context.
            pendingStartMs = 0L;
            pendingAdapter = "";
            pendingAddress = "";
            appendLineLocked(summary.toJson().toString());
        }
    }

    /**
     * Returns up to {@code n} summaries, newest first. {@code n <= 0} returns empty. Reads the
     * whole file but bounds memory by stopping once the tail window is filled.
     */
    List<SessionSummary> getRecent(int n) {
        if (n <= 0) {
            return Collections.emptyList();
        }
        synchronized (lock) {
            List<String> lines = readAllLinesLocked();
            int from = Math.max(0, lines.size() - n);
            List<SessionSummary> out = new ArrayList<>(lines.size() - from);
            for (int i = lines.size() - 1; i >= from; i--) {
                try {
                    JSONObject o = new JSONObject(lines.get(i));
                    SessionSummary s = SessionSummary.fromJson(o);
                    if (s != null) {
                        out.add(s);
                    }
                } catch (JSONException ignored) {
                    // Skip malformed lines rather than failing the whole call.
                }
            }
            return out;
        }
    }

    /** Returns all summaries as JSON-array string for the JS bridge. Newest first. */
    String getRecentAsJsonArray(int n) {
        List<SessionSummary> recent = getRecent(n);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(recent.get(i).toJson().toString());
        }
        sb.append(']');
        return sb.toString();
    }

    private void appendLineLocked(String line) {
        if (!ensureParentDir()) {
            return;
        }
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(line);
            w.write('\n');
        } catch (IOException ignored) {
            return;
        }
        truncateIfNeededLocked();
    }

    private void truncateIfNeededLocked() {
        List<String> lines = readAllLinesLocked();
        if (lines.size() <= MAX_SESSIONS) {
            return;
        }
        List<String> tail = lines.subList(lines.size() - MAX_SESSIONS, lines.size());
        try (FileWriter w = new FileWriter(file, false)) {
            for (String line : tail) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException ignored) {
            // Leave the file as-is; next recordEnd will retry truncation.
        }
    }

    private List<String> readAllLinesLocked() {
        List<String> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        } catch (IOException ignored) {
            // Return what we have.
        }
        return out;
    }

    private boolean ensureParentDir() {
        File parent = file.getParentFile();
        if (parent == null) {
            return false;
        }
        if (parent.isDirectory()) {
            return true;
        }
        return parent.mkdirs();
    }
}
