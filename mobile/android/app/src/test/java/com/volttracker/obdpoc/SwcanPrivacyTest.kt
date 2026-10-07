package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Sensitive SW-CAN frames never reach a log, and the app and the capture tool agree on which. */
class SwcanPrivacyTest {
    private fun frames(vararg lines: String): List<SwcanFrame> =
        SwcanFrameDecoder.parseMonitorOutput(lines.joinToString("\r"))

    @Test
    fun sensitiveFramesAreDroppedAndTheRestReprinted() {
        // A door frame, a GPS frame (arb 0x155), a passive-entry frame (arb 0x146) and an 11-bit one.
        val logged =
            SwcanPrivacy.loggable(
                frames("0c 2f 60 40 01", "10 2A A0 97 01 02 03 04", "10 28 C0 40 05 06", "7E8 03 41 0D 00"),
            )
        assertEquals("0C 2F 60 40 01", logged)
    }

    @Test
    fun theFuelFrameKeepsOnlyItsFuelDoorByte() {
        assertEquals("10 76 40 97 08", SwcanPrivacy.loggable(frames("10 76 40 97 08 11 22 33 44 55")))
    }

    @Test
    fun theCaptureToolMarksTheSameFramesSensitive() {
        val tool = locate("tools/swcan_correlate.py").readText()
        val block = Regex("""MUST_BE_SENSITIVE = \{([^}]*)\}""").find(tool)!!.groupValues[1]
        val pids =
            Regex("""0x([0-9A-Fa-f]+)""")
                .findAll(block.lines().joinToString("\n") { it.substringBefore('#') })
                .map { it.groupValues[1].toInt(16) }
                .toSet()
        assertEquals(pids, SwcanPrivacy.SENSITIVE_PIDS)
    }

    /** Walks up from the test working directory (the module dir under Gradle, `app/` in some IDEs). */
    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative searching upward from " + System.getProperty("user.dir"))
    }
}
