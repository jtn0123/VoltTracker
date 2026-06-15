package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Verifies the diagnostics zip contents and the [Intent] shape. The zip is what the user (and
 * through them, an engineer) sees when they tap "share diagnostics", so a regression where we drop
 * the rolling app log or the summary file would silently degrade every future field report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsShareIntentTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        wipe(File(context.filesDir, "obd-logs"))
        wipe(File(context.filesDir, "app-log"))
        wipe(File(context.cacheDir, "diagnostics"))
        // FileProvider caches a per-authority PathStrategy keyed to the cacheDir it first saw. Each
        // Robolectric test gets a fresh temp cacheDir, so a strategy cached by an earlier test points
        // at a stale root and makes getUriForFile throw here. Clear it so every test rebuilds against
        // its own cacheDir.
        resetFileProviderCache()
    }

    @After
    fun tearDown() {
        wipe(File(context.filesDir, "obd-logs"))
        wipe(File(context.filesDir, "app-log"))
        wipe(File(context.cacheDir, "diagnostics"))
    }

    @Test
    fun zipIncludesRecentSessionLogsAppLogsAndSummary() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())

        // 3 session JSONLs — all under the 5-file cap so all should make it into the zip.
        writeFile(File(obdLogDir, "session-1000-obd.jsonl"), "{\"a\":1}\n")
        writeFile(File(obdLogDir, "session-2000-obd.jsonl"), "{\"a\":2}\n")
        writeFile(File(obdLogDir, "session-3000-obd.jsonl"), "{\"a\":3}\n")
        writeFile(File(obdLogDir, "sessions-summary.jsonl"), "{\"sum\":1}\n")

        val appLogDir = File(context.filesDir, "app-log")
        assertTrue(appLogDir.mkdirs())
        writeFile(File(appLogDir, "app.log"), "rolling-line-1\n")

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull("buildZip should succeed when source dirs are present", zip)
        assertTrue(
            "zip file should live under cacheDir/diagnostics",
            zip!!.absolutePath.contains("/diagnostics/"),
        )

        val entries = listZip(zip)
        assertTrue(
            "session log 1 should be in zip: $entries",
            entries.contains("obd-logs/session-1000-obd.jsonl"),
        )
        assertTrue(
            "session log 2 should be in zip: $entries",
            entries.contains("obd-logs/session-2000-obd.jsonl"),
        )
        assertTrue(
            "session log 3 should be in zip: $entries",
            entries.contains("obd-logs/session-3000-obd.jsonl"),
        )
        assertTrue(
            "summary should be in zip: $entries",
            entries.contains("obd-logs/sessions-summary.jsonl"),
        )
        assertTrue("app log should be in zip: $entries", entries.contains("app-log/app.log"))
    }

    @Test
    fun zipCapsSessionLogsAtFiveMostRecent() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())

        // 8 session logs — only the most recent 5 should be included.
        for (i in 1..8) {
            val f = File(obdLogDir, "session-" + (1000L * i) + "-obd.jsonl")
            writeFile(f, "{\"i\":$i}\n")
            // Stamp mtime in increasing order so 8 is newest.
            assertTrue("setLastModified should succeed for $f", f.setLastModified(10_000L * i))
        }

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull(zip)

        val entries = listZip(zip!!)
        var sessionCount = 0
        for (e in entries) {
            if (e.startsWith("obd-logs/session-")) {
                sessionCount++
            }
        }
        assertEquals(
            "only the " +
                DiagnosticsShareIntent.MAX_SESSION_LOGS +
                " most recent session logs should ship",
            DiagnosticsShareIntent.MAX_SESSION_LOGS,
            sessionCount,
        )
        // The five newest (4..8) are present; the three oldest (1..3) are NOT.
        for (i in 4..8) {
            assertTrue(
                "session $i should be in zip",
                entries.contains("obd-logs/session-" + (1000L * i) + "-obd.jsonl"),
            )
        }
        for (i in 1..3) {
            assertFalse(
                "session $i should be dropped",
                entries.contains("obd-logs/session-" + (1000L * i) + "-obd.jsonl"),
            )
        }
    }

    @Test
    fun zipIncludesRolledAppLogWhenPresent() {
        val appLogDir = File(context.filesDir, "app-log")
        assertTrue(appLogDir.mkdirs())
        writeFile(File(appLogDir, "app.log"), "live\n")
        writeFile(File(appLogDir, "app.log.1"), "rolled\n")

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull(zip)
        val entries = listZip(zip!!)
        assertTrue(entries.contains("app-log/app.log"))
        assertTrue(
            "rolled app log should also be included: $entries",
            entries.contains("app-log/app.log.1"),
        )
    }

    @Test
    fun zipRedactsSensitiveLogTextBeforeSharing() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(
            File(obdLogDir, "session-1-obd.jsonl"),
            "{\"adapter\":\"AA:BB:CC:DD:EE:FF\",\"vin\":\"1G1RD6E45CU112233\"," +
                "\"lat\":32.712345,\"lng\":-117.112233,\"command\":\"010C\"}\n",
        )
        val appLogDir = File(context.filesDir, "app-log")
        assertTrue(appLogDir.mkdirs())
        writeFile(File(appLogDir, "app.log"), "adapter=00:11:22:33:44:55\n")

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull(zip)

        val session = readZipText(zip!!, "obd-logs/session-1-obd.jsonl")
        assertFalse(session.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(session.contains("1G1RD6E45CU112233"))
        assertFalse(session.contains("32.712345"))
        assertTrue(session.contains("[bluetooth-address-redacted]"))
        assertTrue(session.contains("[vin-redacted]"))
        assertTrue(session.contains("[coordinate-redacted]"))
        assertTrue(session.contains("\"command\":\"010C\""))

        val appLog = readZipText(zip, "app-log/app.log")
        assertFalse(appLog.contains("00:11:22:33:44:55"))
        assertTrue(appLog.contains("[bluetooth-address-redacted]"))
    }

    @Test
    fun zipIncludesRedactedDiagnosticsBundleDigest() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(
            File(obdLogDir, "session-1000-obd.jsonl"),
            "{\"ts\":1,\"type\":\"command\",\"adapter\":\"AA:BB:CC:DD:EE:FF\",\"command\":\"010C\"}\n",
        )

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull(zip)

        val entries = listZip(zip!!)
        assertTrue(
            "the AI-facing digest should ship in the zip: $entries",
            entries.contains("diagnostics-bundle.txt"),
        )
        val digest = readZipText(zip, "diagnostics-bundle.txt")
        assertTrue("digest carries the manifest", digest.contains("MANIFEST"))
        assertTrue("digest embeds the session body", digest.contains("session-1000-obd.jsonl"))
        assertFalse("digest must be redacted", digest.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(digest.contains("[bluetooth-address-redacted]"))
    }

    @Test
    fun buildDigestFileWritesRedactedDigestText() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(
            File(obdLogDir, "session-1000-obd.jsonl"),
            "{\"ts\":1,\"type\":\"command\",\"adapter\":\"AA:BB:CC:DD:EE:FF\",\"command\":\"010C\"}\n",
        )

        val digestFile = DiagnosticsShareIntent.buildDigestFile(context)
        assertNotNull("buildDigestFile should succeed when logs are present", digestFile)
        assertTrue(digestFile!!.name.endsWith(".txt"))
        val text = digestFile.readText()
        assertTrue("digest carries the manifest", text.contains("MANIFEST"))
        assertFalse("digest must be redacted", text.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(text.contains("[bluetooth-address-redacted]"))
    }

    @Test
    fun buildDigestIntentReturnsTextShare() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(File(obdLogDir, "session-1000-obd.jsonl"), "{\"ts\":1,\"type\":\"command\"}\n")

        val intent = DiagnosticsShareIntent.buildDigestIntent(context)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("text/plain", intent.type)
        assertNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM))
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun buildDigestIntentReturnsNullWhenContextIsNull() {
        assertNull(DiagnosticsShareIntent.buildDigestIntent(null))
    }

    @Test
    fun buildIntentReturnsShareIntentPointingAtZip() {
        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(File(obdLogDir, "session-1-obd.jsonl"), "{}\n")

        val intent = DiagnosticsShareIntent.buildIntent(context)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("application/zip", intent.type)
        assertNotNull(
            "EXTRA_STREAM URI must be attached",
            intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM),
        )
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION must be set so the share-sheet target can read",
            (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun buildIntentReturnsNullWhenContextIsNull() {
        assertNull(DiagnosticsShareIntent.buildIntent(null))
    }

    @Test
    fun buildZipClearsStaleZipsFromPriorRuns() {
        val diagDir = File(context.cacheDir, "diagnostics")
        assertTrue(diagDir.mkdirs())
        val stale = File(diagDir, "diagnostics-old.zip")
        writeFile(stale, "junk")
        assertTrue(stale.exists())

        val obdLogDir = File(context.filesDir, "obd-logs")
        assertTrue(obdLogDir.mkdirs())
        writeFile(File(obdLogDir, "session-1-obd.jsonl"), "{}\n")

        val zip = DiagnosticsShareIntent.buildZip(context)
        assertNotNull(zip)
        assertFalse("stale zip from prior run should have been cleared", stale.exists())
        assertTrue("new zip should exist", zip!!.exists())
    }

    // ---- helpers ------------------------------------------------------------

    companion object {
        @Throws(IOException::class)
        private fun listZip(zip: File): Set<String> {
            val entries = HashSet<String>()
            ZipInputStream(FileInputStream(zip)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    entries.add(e.name)
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            return entries
        }

        @Throws(IOException::class)
        private fun readZipText(
            zip: File,
            entryName: String,
        ): String {
            ZipInputStream(FileInputStream(zip)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == entryName) {
                        return String(zis.readBytes(), StandardCharsets.UTF_8)
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            throw AssertionError("missing zip entry $entryName")
        }

        @Throws(IOException::class)
        private fun writeFile(
            f: File,
            contents: String,
        ) {
            val parent = f.parentFile
            parent?.mkdirs()
            FileWriter(f, false).use { w ->
                w.write(contents)
            }
        }

        private fun resetFileProviderCache() {
            try {
                val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
                field.isAccessible = true
                (field.get(null) as? MutableMap<*, *>)?.clear()
            } catch (_: ReflectiveOperationException) {
                // Field renamed in a future androidx version: tests that build a share intent may
                // become order-sensitive again, but we don't want to mask that with a crash here.
            }
        }

        private fun wipe(f: File?) {
            if (f == null || !f.exists()) return
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids != null) {
                    for (k in kids) wipe(k)
                }
            }
            f.delete()
        }
    }
}
