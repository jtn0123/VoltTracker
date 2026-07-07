package com.volttracker.obdpoc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [RollingAppLog]'s append behaviour and 7-day rotation. Rotation is the only non-trivial
 * path here, and the only one that's previously gone wrong in similar code: a stale lastModified
 * check or a missed rename leaves the file growing forever.
 */
class RollingAppLogTest {
    private lateinit var dir: File
    private lateinit var clock: AtomicLong
    private lateinit var log: RollingAppLog

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ralog").toFile()
        // Start the simulated clock at a real-ish "now" so the rotation tests can subtract the
        // 7-day window without sliding into negative file timestamps (File.setLastModified
        // rejects negative).
        clock = AtomicLong(1_700_000_000_000L)
        log = RollingAppLog(dir, clock::get)
    }

    @After
    fun tearDown() {
        deleteRec(dir)
    }

    @Test
    fun writeCreatesLogFileAndAppendsLine() {
        log.write("D", "tag", "hello")

        val live = log.liveFile()
        assertTrue("live log file should exist after first write", live.exists())
        val contents = readAll(live)
        assertTrue("line should contain level", contents.contains(" D "))
        assertTrue("line should contain tag", contents.contains("tag:"))
        assertTrue("line should contain message", contents.contains("hello"))
    }

    @Test
    fun writeAppendsMultipleLines() {
        log.write("D", "tag", "first")
        log.write("W", "tag", "second")
        log.write("E", "tag", "third")

        val contents = readAll(log.liveFile())
        // Kotlin's split keeps the trailing empty token after the final "\n"
        // (Java's split drops it); dropLastWhile matches the original semantics.
        val lines = contents.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("first"))
        assertTrue(lines[1].contains("second"))
        assertTrue(lines[2].contains("third"))
    }

    @Test
    fun newlinesInMessageAreFlattened() {
        // Multi-line messages must not be split across lines, or grep-one-event-per-line breaks.
        log.write("D", "tag", "line1\nline2\rline3")

        val contents = readAll(log.liveFile())
        // Kotlin's split keeps the trailing empty token after the final "\n"
        // (Java's split drops it); dropLastWhile matches the original semantics.
        val lines = contents.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
        assertEquals(
            "embedded newlines must be flattened, not produce multiple log lines",
            1,
            lines.size,
        )
        assertTrue(lines[0].contains("line1 line2 line3"))
    }

    @Test
    fun nullTagAndMessageRenderAsDash() {
        log.write(null, null, null)
        val contents = readAll(log.liveFile())
        assertTrue(
            "null fields should render as dash, not throw: $contents",
            contents.contains("- -: -"),
        )
    }

    @Test
    fun writeDropsLineWhenLogDirCannotBeCreated() {
        val parentFile = File(dir, "not-a-directory").apply { writeText("blocks child mkdirs") }
        val blockedDir = File(parentFile, "app-log")
        val blockedLog = RollingAppLog(blockedDir, clock::get)

        blockedLog.write("D", "tag", "dropped")

        assertFalse("mkdir failure should leave the blocked log dir absent", blockedDir.exists())
    }

    @Test
    fun writeDropsLineWhenLiveLogPathIsADirectory() {
        val livePath = log.liveFile()
        assertTrue(livePath.mkdirs())

        log.write("D", "tag", "dropped")

        assertTrue("the bad live path should remain a directory after the failed append", livePath.isDirectory)
    }

    @Test
    fun freshLogYoungerThan7DaysIsNotRotated() {
        log.write("D", "tag", "fresh")

        // Advance clock 6 days — still below the 7-day threshold.
        clock.addAndGet(6L * 24L * 60L * 60L * 1000L)

        log.write("D", "tag", "still-fresh")

        assertTrue(log.liveFile().exists())
        assertFalse("must not roll before 7 days", log.rolledFile().exists())
    }

    @Test
    fun existingLiveFileWithoutBornMarkerIsTreatedAsFresh() {
        assertTrue(dir.exists())
        log.liveFile().writeText("legacy\n")
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        log.write("D", "tag", "after-missing-born")

        assertFalse("missing born marker should skip rotation", log.rolledFile().exists())
        val live = readAll(log.liveFile())
        assertTrue(live.contains("legacy"))
        assertTrue(live.contains("after-missing-born"))
        assertTrue("a fresh born marker should be written for later age checks", File(dir, "app.log.born").exists())
    }

    @Test
    fun invalidBornMarkerIsTreatedAsFresh() {
        assertTrue(dir.exists())
        log.liveFile().writeText("legacy\n")
        File(dir, "app.log.born").writeText("not-a-timestamp\n")
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        log.write("D", "tag", "after-invalid-born")

        assertFalse("unparseable born marker should not rotate spuriously", log.rolledFile().exists())
        val live = readAll(log.liveFile())
        assertTrue(live.contains("legacy"))
        assertTrue(live.contains("after-invalid-born"))
    }

    @Test
    fun logOlderThan7DaysRollsToRolledFileOnNextWrite() {
        log.write("D", "tag", "first-week")
        // Advance the simulated clock past the 7-day window. RollingAppLog now anchors rotation
        // to the birth sidecar (written on the first append above), not liveFile.lastModified()
        // — every append touches mtime, so an active log would never rotate under the old check.
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        // Next write should roll and start a fresh live file.
        log.write("D", "tag", "second-week")

        assertTrue("rolled file should be present", log.rolledFile().exists())
        assertTrue(
            "rolled file should contain the old line",
            readAll(log.rolledFile()).contains("first-week"),
        )
        assertTrue(
            "live file should contain the new line",
            readAll(log.liveFile()).contains("second-week"),
        )
        assertFalse(
            "live file should not contain the old (rotated) line",
            readAll(log.liveFile()).contains("first-week"),
        )
    }

    @Test
    fun staleLiveLogStaysInPlaceWhenRolledFileCannotBeDeleted() {
        log.write("D", "tag", "first-week")
        val blockedRolled = log.rolledFile()
        assertTrue(blockedRolled.mkdirs())
        File(blockedRolled, "child").writeText("prevents directory delete")
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        log.write("D", "tag", "second-week")

        assertTrue("blocked rolled path should still be a directory", blockedRolled.isDirectory)
        val live = readAll(log.liveFile())
        assertTrue("old line should stay in live file when roll cannot proceed", live.contains("first-week"))
        assertTrue("new line should still be appended instead of being dropped", live.contains("second-week"))
    }

    @Test
    fun rotationOverwritesPreviousRolledFile() {
        // Manually create a stale rolled file — the rotation logic should overwrite it.
        val rolled = log.rolledFile()
        assertTrue(dir.exists())
        Files.write(rolled.toPath(), "older-rolled-content\n".toByteArray())

        log.write("D", "tag", "first-week")
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        log.write("D", "tag", "second-week")

        // After rotation, the rolled file must NOT contain the previous "older-rolled-content".
        val rolledContents = readAll(rolled)
        assertFalse(
            "previous rolled file should have been overwritten: $rolledContents",
            rolledContents.contains("older-rolled-content"),
        )
        assertTrue(
            "rolled file should now hold the just-rotated live content",
            rolledContents.contains("first-week"),
        )
    }

    @Test
    fun heldWriterIsReusedAcrossManyAppendsAndReopenedAfterRotation() {
        // The append path now holds a long-lived buffered writer instead of reopening per line (G1).
        // Prove that many appends through the held writer all land, that each is flushed so an
        // immediate read sees it, and that a rotation in the middle closes+reopens the writer
        // without losing the lines on either side of the roll.
        for (i in 0 until 50) {
            log.write("D", "tag", "pre-$i")
            // Each line must be visible immediately (flush-per-write), not buffered until close.
            assertTrue("line pre-$i must be flushed right away", readAll(log.liveFile()).contains("pre-$i"))
        }

        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)
        log.write("D", "tag", "after-roll") // triggers rotation: writer closed, live file renamed

        // The pre-roll lines went to the rolled file; the post-roll line is in a fresh live file
        // written through the reopened writer.
        val rolled = readAll(log.rolledFile())
        assertTrue("rolled file holds the last pre-roll line", rolled.contains("pre-49"))
        assertFalse("live file must not still hold pre-roll lines", readAll(log.liveFile()).contains("pre-49"))

        for (i in 0 until 10) {
            log.write("D", "tag", "post-$i")
        }
        val live = readAll(log.liveFile())
        assertTrue("first post-roll line lands in the reopened live file", live.contains("after-roll"))
        assertTrue("subsequent post-roll lines append through the reused writer", live.contains("post-9"))
    }

    @Test
    fun closeReleasesTheWriterAndDropsFurtherWritesInsteadOfReopening() {
        // close() releases the held file handle (called from the service's onDestroy so the
        // long-lived writer isn't leaked for the process lifetime) without losing already-flushed
        // lines. It is permanent: a straggler write from a still-draining thread is dropped rather
        // than reopening — and re-leaking — the handle.
        log.write("D", "tag", "before-close")
        log.close()
        assertTrue("flushed line survives close", readAll(log.liveFile()).contains("before-close"))

        // close() is idempotent, and a post-close write is a no-op (no reopen, no new line).
        log.close()
        log.write("D", "tag", "after-close")
        val contents = readAll(log.liveFile())
        assertTrue("pre-close line is still present", contents.contains("before-close"))
        assertFalse("post-close write is dropped, not reopened", contents.contains("after-close"))
    }

    @Test
    fun rotationIsTriggeredOncePerRollAge() {
        log.write("D", "tag", "v1")
        clock.addAndGet(RollingAppLog.ROTATE_AGE_MS + 1L)

        log.write("D", "tag", "v2") // triggers rotation; born marker resets to the current clock
        log.write("D", "tag", "v3") // does NOT trigger again — fresh born marker means age ~= 0

        // Live file should contain v2 and v3, rolled file should contain only v1.
        val liveContents = readAll(log.liveFile())
        assertTrue(liveContents.contains("v2"))
        assertTrue(liveContents.contains("v3"))
        val rolledContents = readAll(log.rolledFile())
        assertTrue(rolledContents.contains("v1"))
        assertFalse(rolledContents.contains("v2"))
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
