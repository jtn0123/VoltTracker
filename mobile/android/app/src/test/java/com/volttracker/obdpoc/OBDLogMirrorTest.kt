package com.volttracker.obdpoc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Files

/**
 * Verifies the [OBDLog.mirror] install hook: once installed, every `event/warn/error` call also
 * tees into the rolling log; once detached, calls go back to logcat-only.
 */
class OBDLogMirrorTest {
    private lateinit var dir: File
    private lateinit var log: RollingAppLog

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("obdlogmirror").toFile()
        log = RollingAppLog(dir)
    }

    @After
    fun tearDown() {
        // Detach so other tests aren't affected by the mirror persisting.
        OBDLog.mirror(null)
        deleteRec(dir)
    }

    @Test
    fun eventCallTeesToRollingLogAfterMirrorInstall() {
        OBDLog.mirror(log)
        OBDLog.event("mytag", "my_event", mapOf("k" to "v"))

        val contents = readAll(log.liveFile())
        assertTrue(
            "event call should land in rolling log: $contents",
            contents.contains("my_event"),
        )
        assertTrue("structured field should also be there: $contents", contents.contains("k=v"))
    }

    @Test
    fun warnAndErrorTeeToRollingLog() {
        OBDLog.mirror(log)
        OBDLog.warn("svc", "uh-oh")
        OBDLog.error("svc", "very-bad")

        val contents = readAll(log.liveFile())
        assertTrue(contents.contains("W svc: uh-oh"))
        assertTrue(contents.contains("E svc: very-bad"))
    }

    @Test
    fun detachingMirrorStopsRollingLogWrites() {
        OBDLog.mirror(log)
        OBDLog.warn("svc", "first")
        OBDLog.mirror(null)
        OBDLog.warn("svc", "second")

        val contents = readAll(log.liveFile())
        assertTrue(contents.contains("first"))
        assertFalse(
            "post-detach call must not appear in the rolling log: $contents",
            contents.contains("second"),
        )
    }

    @Test
    fun callingEventWithoutMirrorIsSafe() {
        // No mirror installed at all — must not throw and must not create a log file.
        OBDLog.mirror(null)
        OBDLog.event("tag", "evt", mapOf("k" to "v"))
        OBDLog.warn("tag", "warn")
        OBDLog.error("tag", "err")

        // The rolling-log file should not have been created since nothing wrote to it.
        assertFalse(log.liveFile().exists())
    }

    // ---- helpers ------------------------------------------------------------

    private companion object {
        @Throws(IOException::class)
        fun readAll(f: File): String {
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

        fun deleteRec(f: File?) {
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
