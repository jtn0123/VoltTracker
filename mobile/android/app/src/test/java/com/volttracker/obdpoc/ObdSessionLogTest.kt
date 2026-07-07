package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Files

/**
 * Tests for [ObdSessionLog] — focuses on the new crash-safe `writeDurable` path (Bucket 3 / A9) and
 * pins backward compatibility for the existing `write` API used by [SessionRecorder].
 */
class ObdSessionLogTest {
    private lateinit var dir: File

    @Before
    @Throws(IOException::class)
    fun setUp() {
        dir = Files.createTempDirectory("oblog").toFile()
    }

    @After
    fun tearDown() {
        deleteRec(dir)
    }

    @Test
    @Throws(IOException::class, JSONException::class)
    fun writeAppendsLineToSessionFile() {
        val log = ObdSessionLog(dir)
        log.open("obd")

        val payload = JSONObject().put("k", "v")
        log.write("event", payload)
        log.close()

        val session = sessionFile(dir)
        assertNotNull(session)
        val contents = readAll(session!!)
        assertTrue(
            "should write a JSON line: $contents",
            contents.contains("\"type\":\"event\""),
        )
        assertTrue("payload should be embedded", contents.contains("\"k\":\"v\""))
    }

    @Test
    @Throws(IOException::class, JSONException::class)
    fun writeDurableAlsoLandsTheLine() {
        // Functionally, writeDurable should be observable like write — same on-disk line, just
        // with an extra fsync. We can't directly observe the sync from a unit test, but we can
        // assert the line is on disk and parseable as JSON.
        val log = ObdSessionLog(dir)
        log.open("obd")

        log.writeDurable("error", JSONObject().put("kind", "io"))
        log.close()

        val session = sessionFile(dir)
        val contents = readAll(session!!)
        assertTrue(
            "durable write should still land the line on disk: $contents",
            contents.contains("\"type\":\"error\""),
        )
        assertTrue(contents.contains("\"kind\":\"io\""))
    }

    @Test
    fun writeBeforeOpenIsNoOp() {
        val log = ObdSessionLog(dir)
        // No open() — write must not throw and the directory must stay empty.
        log.write("event", JSONObject())
        log.writeDurable("error", JSONObject())

        val kids = dir.listFiles()
        assertTrue("no session file should be created", kids == null || kids.isEmpty())
    }

    @Test
    @Throws(IOException::class)
    fun openFailureIsSurfacedForDiagnostics() {
        val notDirectory = File(dir, "obd-logs")
        Files.write(notDirectory.toPath(), byteArrayOf(1, 2, 3))
        val log = ObdSessionLog(notDirectory)

        log.open("obd")

        assertFalse(log.isOpen())
        assertTrue(log.lastWriteFailure()!!.contains("open_mkdir_failed"))
    }

    @Test
    @Throws(IOException::class)
    fun openFailureIsSurfacedWhenParentPathBlocksMkdir() {
        val parentFile = File(dir, "not-a-directory").apply { writeText("blocks child mkdirs") }
        val blockedDir = File(parentFile, "obd-logs")
        val log = ObdSessionLog(blockedDir)

        log.open("obd")

        assertFalse(log.isOpen())
        assertTrue(log.lastWriteFailure()!!.contains("open_mkdir_failed"))
    }

    @Test
    fun openFailureIsSurfacedWhenModeCreatesNestedFilePath() {
        val log = ObdSessionLog(dir)

        log.open("bad/mode")

        assertFalse(log.isOpen())
        assertTrue(log.lastWriteFailure()!!.contains("open_failed"))
    }

    @Test
    fun successfulWriteClearsLatestPointerFailure() {
        assertTrue(File(dir, "latest.txt").mkdir())
        val log = ObdSessionLog(dir)

        log.open("obd")
        assertTrue(log.isOpen())
        assertTrue(log.lastWriteFailure()!!.contains("latest_pointer_failed"))

        log.write("event", JSONObject().put("ok", true))

        assertEquals("a later successful write should clear the stale pointer failure", null, log.lastWriteFailure())
    }

    @Test
    fun fileNameIsNullWhenClosedAndPopulatedWhenOpen() {
        val log = ObdSessionLog(dir)
        assertEquals(null, log.fileName())
        log.open("scan")
        assertNotNull(log.fileName())
        assertTrue("filename should mention the mode", log.fileName()!!.contains("scan"))
        log.close()
        assertEquals("filename should clear on close", null, log.fileName())
    }

    @Test
    @Throws(IOException::class)
    fun reopenClosesPreviousFileSoNextWriteGoesToFreshFile() {
        val log = ObdSessionLog(dir)
        log.open("obd")
        log.write("event", JSONObject())
        val firstName = log.fileName()

        // Wait until the wall clock ticks at least once. A fixed Thread.sleep(2) can't reliably
        // advance currentTimeMillis on a coarse-grained clock (CI runners we've seen with ~10 ms
        // granularity), so firstName/secondName would sporadically collide.
        val tick = System.currentTimeMillis()
        val deadline = tick + 100L
        while (System.currentTimeMillis() == tick && System.currentTimeMillis() < deadline) {
            Thread.yield()
        }
        log.open("obd")
        val secondName = log.fileName()

        assertNotNull(firstName)
        assertNotNull(secondName)
        assertFalse("reopen must create a fresh file", firstName == secondName)
    }

    @Test
    @Throws(IOException::class)
    fun latestPointerFileTracksCurrentSession() {
        val log = ObdSessionLog(dir)
        log.open("obd")
        val current = log.fileName()

        val pointer = File(dir, "latest.txt")
        assertTrue("latest pointer file should be written on open", pointer.exists())
        val pointed = readAll(pointer).trim()
        assertEquals("pointer should name the current session log", current, pointed)
    }

    // ---- helpers ------------------------------------------------------------

    private companion object {
        private fun sessionFile(dir: File): File? {
            val kids = dir.listFiles { _, name -> name.startsWith("session-") }
            return if (kids == null || kids.isEmpty()) null else kids[0]
        }

        @Throws(IOException::class)
        private fun readAll(f: File): String {
            val sb = StringBuilder()
            BufferedReader(FileReader(f)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
            return sb.toString()
        }

        private fun deleteRec(f: File?) {
            if (f == null) return
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids != null) {
                    for (k in kids) deleteRec(k)
                }
            }
            @Suppress("UNUSED_VARIABLE")
            val ignored = f.delete()
        }
    }
}
