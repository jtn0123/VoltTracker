package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Verifies the diagnostics zip contents and the {@link Intent} shape. The zip is what the user (and
 * through them, an engineer) sees when they tap "share diagnostics", so a regression where we drop
 * the rolling app log or the summary file would silently degrade every future field report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DiagnosticsShareIntentTest {

    private Context context;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        wipe(new File(context.getFilesDir(), "obd-logs"));
        wipe(new File(context.getFilesDir(), "app-log"));
        wipe(new File(context.getCacheDir(), "diagnostics"));
    }

    @After
    public void tearDown() {
        wipe(new File(context.getFilesDir(), "obd-logs"));
        wipe(new File(context.getFilesDir(), "app-log"));
        wipe(new File(context.getCacheDir(), "diagnostics"));
    }

    @Test
    public void zipIncludesRecentSessionLogsAppLogsAndSummary() throws IOException {
        File obdLogDir = new File(context.getFilesDir(), "obd-logs");
        assertTrue(obdLogDir.mkdirs());

        // 3 session JSONLs — all under the 5-file cap so all should make it into the zip.
        writeFile(new File(obdLogDir, "session-1000-obd.jsonl"), "{\"a\":1}\n");
        writeFile(new File(obdLogDir, "session-2000-obd.jsonl"), "{\"a\":2}\n");
        writeFile(new File(obdLogDir, "session-3000-obd.jsonl"), "{\"a\":3}\n");
        writeFile(new File(obdLogDir, "sessions-summary.jsonl"), "{\"sum\":1}\n");

        File appLogDir = new File(context.getFilesDir(), "app-log");
        assertTrue(appLogDir.mkdirs());
        writeFile(new File(appLogDir, "app.log"), "rolling-line-1\n");

        File zip = DiagnosticsShareIntent.buildZip(context);
        assertNotNull("buildZip should succeed when source dirs are present", zip);
        assertTrue(
                "zip file should live under cacheDir/diagnostics",
                zip.getAbsolutePath().contains("/diagnostics/"));

        Set<String> entries = listZip(zip);
        assertTrue(
                "session log 1 should be in zip: " + entries,
                entries.contains("obd-logs/session-1000-obd.jsonl"));
        assertTrue(
                "session log 2 should be in zip: " + entries,
                entries.contains("obd-logs/session-2000-obd.jsonl"));
        assertTrue(
                "session log 3 should be in zip: " + entries,
                entries.contains("obd-logs/session-3000-obd.jsonl"));
        assertTrue(
                "summary should be in zip: " + entries,
                entries.contains("obd-logs/sessions-summary.jsonl"));
        assertTrue("app log should be in zip: " + entries, entries.contains("app-log/app.log"));
    }

    @Test
    public void zipCapsSessionLogsAtFiveMostRecent() throws IOException {
        File obdLogDir = new File(context.getFilesDir(), "obd-logs");
        assertTrue(obdLogDir.mkdirs());

        // 8 session logs — only the most recent 5 should be included.
        for (int i = 1; i <= 8; i++) {
            File f = new File(obdLogDir, "session-" + (1000L * i) + "-obd.jsonl");
            writeFile(f, "{\"i\":" + i + "}\n");
            // Stamp mtime in increasing order so 8 is newest.
            assertTrue("setLastModified should succeed for " + f, f.setLastModified(10_000L * i));
        }

        File zip = DiagnosticsShareIntent.buildZip(context);
        assertNotNull(zip);

        Set<String> entries = listZip(zip);
        int sessionCount = 0;
        for (String e : entries) {
            if (e.startsWith("obd-logs/session-")) {
                sessionCount++;
            }
        }
        assertEquals(
                "only the "
                        + DiagnosticsShareIntent.MAX_SESSION_LOGS
                        + " most recent session logs should ship",
                DiagnosticsShareIntent.MAX_SESSION_LOGS,
                sessionCount);
        // The five newest (4..8) are present; the three oldest (1..3) are NOT.
        for (int i = 4; i <= 8; i++) {
            assertTrue(
                    "session " + i + " should be in zip",
                    entries.contains("obd-logs/session-" + (1000L * i) + "-obd.jsonl"));
        }
        for (int i = 1; i <= 3; i++) {
            assertFalse(
                    "session " + i + " should be dropped",
                    entries.contains("obd-logs/session-" + (1000L * i) + "-obd.jsonl"));
        }
    }

    @Test
    public void zipIncludesRolledAppLogWhenPresent() throws IOException {
        File appLogDir = new File(context.getFilesDir(), "app-log");
        assertTrue(appLogDir.mkdirs());
        writeFile(new File(appLogDir, "app.log"), "live\n");
        writeFile(new File(appLogDir, "app.log.1"), "rolled\n");

        File zip = DiagnosticsShareIntent.buildZip(context);
        assertNotNull(zip);
        Set<String> entries = listZip(zip);
        assertTrue(entries.contains("app-log/app.log"));
        assertTrue(
                "rolled app log should also be included: " + entries,
                entries.contains("app-log/app.log.1"));
    }

    @Test
    public void buildIntentReturnsShareIntentPointingAtZip() throws IOException {
        File obdLogDir = new File(context.getFilesDir(), "obd-logs");
        assertTrue(obdLogDir.mkdirs());
        writeFile(new File(obdLogDir, "session-1-obd.jsonl"), "{}\n");

        Intent intent = DiagnosticsShareIntent.buildIntent(context);
        assertNotNull(intent);
        assertEquals(Intent.ACTION_SEND, intent.getAction());
        assertEquals("application/zip", intent.getType());
        assertNotNull(
                "EXTRA_STREAM URI must be attached",
                intent.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue(
                "FLAG_GRANT_READ_URI_PERMISSION must be set so the share-sheet target can read",
                (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void buildIntentReturnsNullWhenContextIsNull() {
        assertNull(DiagnosticsShareIntent.buildIntent(null));
    }

    @Test
    public void buildZipClearsStaleZipsFromPriorRuns() throws IOException {
        File diagDir = new File(context.getCacheDir(), "diagnostics");
        assertTrue(diagDir.mkdirs());
        File stale = new File(diagDir, "diagnostics-old.zip");
        writeFile(stale, "junk");
        assertTrue(stale.exists());

        File obdLogDir = new File(context.getFilesDir(), "obd-logs");
        assertTrue(obdLogDir.mkdirs());
        writeFile(new File(obdLogDir, "session-1-obd.jsonl"), "{}\n");

        File zip = DiagnosticsShareIntent.buildZip(context);
        assertNotNull(zip);
        assertFalse("stale zip from prior run should have been cleared", stale.exists());
        assertTrue("new zip should exist", zip.exists());
    }

    // ---- helpers ------------------------------------------------------------

    private static Set<String> listZip(File zip) throws IOException {
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.add(e.getName());
                zis.closeEntry();
            }
        }
        return entries;
    }

    private static void writeFile(File f, String contents) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(contents);
        }
    }

    private static void wipe(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) wipe(k);
            }
        }
        boolean ignored = f.delete();
    }
}
