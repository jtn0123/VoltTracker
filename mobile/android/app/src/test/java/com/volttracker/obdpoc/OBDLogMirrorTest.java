package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the {@link OBDLog#mirror(RollingAppLog)} install hook: once installed, every {@code
 * event/warn/error} call also tees into the rolling log; once detached, calls go back to
 * logcat-only.
 */
public class OBDLogMirrorTest {

    private File dir;
    private RollingAppLog log;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("obdlogmirror").toFile();
        log = new RollingAppLog(dir);
    }

    @After
    public void tearDown() {
        // Detach so other tests aren't affected by the mirror persisting.
        OBDLog.mirror(null);
        deleteRec(dir);
    }

    @Test
    public void eventCallTeesToRollingLogAfterMirrorInstall() throws IOException {
        OBDLog.mirror(log);
        OBDLog.event("mytag", "my_event", Collections.singletonMap("k", "v"));

        String contents = readAll(log.liveFile());
        assertTrue(
                "event call should land in rolling log: " + contents,
                contents.contains("my_event"));
        assertTrue("structured field should also be there: " + contents, contents.contains("k=v"));
    }

    @Test
    public void warnAndErrorTeeToRollingLog() throws IOException {
        OBDLog.mirror(log);
        OBDLog.warn("svc", "uh-oh");
        OBDLog.error("svc", "very-bad");

        String contents = readAll(log.liveFile());
        assertTrue(contents.contains("W svc: uh-oh"));
        assertTrue(contents.contains("E svc: very-bad"));
    }

    @Test
    public void detachingMirrorStopsRollingLogWrites() throws IOException {
        OBDLog.mirror(log);
        OBDLog.warn("svc", "first");
        OBDLog.mirror(null);
        OBDLog.warn("svc", "second");

        String contents = readAll(log.liveFile());
        assertTrue(contents.contains("first"));
        assertFalse(
                "post-detach call must not appear in the rolling log: " + contents,
                contents.contains("second"));
    }

    @Test
    public void callingEventWithoutMirrorIsSafe() {
        // No mirror installed at all — must not throw and must not create a log file.
        OBDLog.mirror(null);
        OBDLog.event("tag", "evt", Collections.singletonMap("k", "v"));
        OBDLog.warn("tag", "warn");
        OBDLog.error("tag", "err");

        // The rolling-log file should not have been created since nothing wrote to it.
        assertFalse(log.liveFile().exists());
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
