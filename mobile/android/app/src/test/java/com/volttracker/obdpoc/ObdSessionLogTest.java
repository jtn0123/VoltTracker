package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ObdSessionLog} — focuses on the new crash-safe {@code writeDurable} path (Bucket
 * 3 / A9) and pins backward compatibility for the existing {@code write} API used by {@link
 * SessionRecorder}.
 */
public class ObdSessionLogTest {

    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("oblog").toFile();
    }

    @After
    public void tearDown() {
        deleteRec(dir);
    }

    @Test
    public void writeAppendsLineToSessionFile() throws IOException, JSONException {
        ObdSessionLog log = new ObdSessionLog(dir);
        log.open("obd");

        JSONObject payload = new JSONObject().put("k", "v");
        log.write("event", payload);
        log.close();

        File session = sessionFile(dir);
        assertNotNull(session);
        String contents = readAll(session);
        assertTrue(
                "should write a JSON line: " + contents, contents.contains("\"type\":\"event\""));
        assertTrue("payload should be embedded", contents.contains("\"k\":\"v\""));
    }

    @Test
    public void writeDurableAlsoLandsTheLine() throws IOException, JSONException {
        // Functionally, writeDurable should be observable like write — same on-disk line, just
        // with an extra fsync. We can't directly observe the sync from a unit test, but we can
        // assert the line is on disk and parseable as JSON.
        ObdSessionLog log = new ObdSessionLog(dir);
        log.open("obd");

        log.writeDurable("error", new JSONObject().put("kind", "io"));
        log.close();

        File session = sessionFile(dir);
        String contents = readAll(session);
        assertTrue(
                "durable write should still land the line on disk: " + contents,
                contents.contains("\"type\":\"error\""));
        assertTrue(contents.contains("\"kind\":\"io\""));
    }

    @Test
    public void writeBeforeOpenIsNoOp() {
        ObdSessionLog log = new ObdSessionLog(dir);
        // No open() — write must not throw and the directory must stay empty.
        log.write("event", new JSONObject());
        log.writeDurable("error", new JSONObject());

        File[] kids = dir.listFiles();
        assertTrue("no session file should be created", kids == null || kids.length == 0);
    }

    @Test
    public void fileNameIsNullWhenClosedAndPopulatedWhenOpen() {
        ObdSessionLog log = new ObdSessionLog(dir);
        assertEquals(null, log.fileName());
        log.open("scan");
        assertNotNull(log.fileName());
        assertTrue("filename should mention the mode", log.fileName().contains("scan"));
        log.close();
        assertEquals("filename should clear on close", null, log.fileName());
    }

    @Test
    public void reopenClosesPreviousFileSoNextWriteGoesToFreshFile() throws IOException {
        ObdSessionLog log = new ObdSessionLog(dir);
        log.open("obd");
        log.write("event", new JSONObject());
        String firstName = log.fileName();

        // Wait until the wall clock ticks at least once. A fixed Thread.sleep(2) can't reliably
        // advance currentTimeMillis on a coarse-grained clock (CI runners we've seen with ~10 ms
        // granularity), so firstName/secondName would sporadically collide.
        long tick = System.currentTimeMillis();
        long deadline = tick + 100L;
        while (System.currentTimeMillis() == tick && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        log.open("obd");
        String secondName = log.fileName();

        assertNotNull(firstName);
        assertNotNull(secondName);
        assertFalse("reopen must create a fresh file", firstName.equals(secondName));
    }

    @Test
    public void latestPointerFileTracksCurrentSession() throws IOException {
        ObdSessionLog log = new ObdSessionLog(dir);
        log.open("obd");
        String current = log.fileName();

        File pointer = new File(dir, "latest.txt");
        assertTrue("latest pointer file should be written on open", pointer.exists());
        String pointed = readAll(pointer).trim();
        assertEquals("pointer should name the current session log", current, pointed);
    }

    // ---- helpers ------------------------------------------------------------

    private static File sessionFile(File dir) {
        File[] kids = dir.listFiles((d, name) -> name.startsWith("session-"));
        return (kids == null || kids.length == 0) ? null : kids[0];
    }

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
