package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link RollingAppLog}'s append behaviour and 7-day rotation. Rotation is the only
 * non-trivial path here, and the only one that's previously gone wrong in similar code: a stale
 * lastModified check or a missed rename leaves the file growing forever.
 */
public class RollingAppLogTest {

    private File dir;
    private AtomicLong clock;
    private RollingAppLog log;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("ralog").toFile();
        // Start the simulated clock at a real-ish "now" so the rotation tests can subtract the
        // 7-day window without sliding into negative file timestamps (File.setLastModified
        // rejects negative).
        clock = new AtomicLong(1_700_000_000_000L);
        log = new RollingAppLog(dir, clock::get);
    }

    @After
    public void tearDown() {
        deleteRec(dir);
    }

    @Test
    public void writeCreatesLogFileAndAppendsLine() throws IOException {
        log.write("D", "tag", "hello");

        File live = log.liveFile();
        assertTrue("live log file should exist after first write", live.exists());
        String contents = readAll(live);
        assertTrue("line should contain level", contents.contains(" D "));
        assertTrue("line should contain tag", contents.contains("tag:"));
        assertTrue("line should contain message", contents.contains("hello"));
    }

    @Test
    public void writeAppendsMultipleLines() throws IOException {
        log.write("D", "tag", "first");
        log.write("W", "tag", "second");
        log.write("E", "tag", "third");

        String contents = readAll(log.liveFile());
        String[] lines = contents.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("first"));
        assertTrue(lines[1].contains("second"));
        assertTrue(lines[2].contains("third"));
    }

    @Test
    public void newlinesInMessageAreFlattened() throws IOException {
        // Multi-line messages must not be split across lines, or grep-one-event-per-line breaks.
        log.write("D", "tag", "line1\nline2\rline3");

        String contents = readAll(log.liveFile());
        String[] lines = contents.split("\n");
        assertEquals(
                "embedded newlines must be flattened, not produce multiple log lines",
                1,
                lines.length);
        assertTrue(lines[0].contains("line1 line2 line3"));
    }

    @Test
    public void nullTagAndMessageRenderAsDash() throws IOException {
        log.write(null, null, null);
        String contents = readAll(log.liveFile());
        assertTrue(
                "null fields should render as dash, not throw: " + contents,
                contents.contains("- -: -"));
    }

    @Test
    public void freshLogYoungerThan7DaysIsNotRotated() throws IOException {
        log.write("D", "tag", "fresh");

        // Advance clock 6 days — still below the 7-day threshold.
        clock.addAndGet(6L * 24L * 60L * 60L * 1000L);

        log.write("D", "tag", "still-fresh");

        assertTrue(log.liveFile().exists());
        assertFalse("must not roll before 7 days", log.rolledFile().exists());
    }

    @Test
    public void logOlderThan7DaysRollsToRolledFileOnNextWrite() throws IOException {
        log.write("D", "tag", "first-week");
        // Advance the simulated clock past the 7-day window. RollingAppLog now anchors rotation
        // to the birth sidecar (written on the first append above), not liveFile.lastModified()
        // — every append touches mtime, so an active log would never rotate under the old check.
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L);

        // Next write should roll and start a fresh live file.
        log.write("D", "tag", "second-week");

        assertTrue("rolled file should be present", log.rolledFile().exists());
        assertTrue(
                "rolled file should contain the old line",
                readAll(log.rolledFile()).contains("first-week"));
        assertTrue(
                "live file should contain the new line",
                readAll(log.liveFile()).contains("second-week"));
        assertFalse(
                "live file should not contain the old (rotated) line",
                readAll(log.liveFile()).contains("first-week"));
    }

    @Test
    public void rotationOverwritesPreviousRolledFile() throws IOException {
        // Manually create a stale rolled file — the rotation logic should overwrite it.
        File rolled = log.rolledFile();
        assertTrue(dir.exists());
        Files.write(rolled.toPath(), "older-rolled-content\n".getBytes());

        log.write("D", "tag", "first-week");
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L);

        log.write("D", "tag", "second-week");

        // After rotation, the rolled file must NOT contain the previous "older-rolled-content".
        String rolledContents = readAll(rolled);
        assertFalse(
                "previous rolled file should have been overwritten: " + rolledContents,
                rolledContents.contains("older-rolled-content"));
        assertTrue(
                "rolled file should now hold the just-rotated live content",
                rolledContents.contains("first-week"));
    }

    @Test
    public void rotationIsTriggeredOncePerRollAge() throws IOException {
        log.write("D", "tag", "v1");
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L);

        log.write("D", "tag", "v2"); // triggers rotation; born marker resets to the current clock
        log.write("D", "tag", "v3"); // does NOT trigger again — fresh born marker means age ~= 0

        // Live file should contain v2 and v3, rolled file should contain only v1.
        String liveContents = readAll(log.liveFile());
        assertTrue(liveContents.contains("v2"));
        assertTrue(liveContents.contains("v3"));
        String rolledContents = readAll(log.rolledFile());
        assertTrue(rolledContents.contains("v1"));
        assertFalse(rolledContents.contains("v2"));
    }

    // ---- helpers ------------------------------------------------------------

    private static String readAll(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        boolean ignored = f.delete();
    }
}
