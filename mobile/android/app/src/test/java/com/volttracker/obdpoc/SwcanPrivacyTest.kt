package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Only allowlisted SW-CAN payloads reach a log, sensitive frames never do, and the app and the capture tool agree on which. */
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
    fun aFrameOffTheAllowlistIsNotReprinted() {
        // Arb 0x3C1 is neither sensitive nor allowlisted: counted elsewhere, never printed.
        assertEquals("", SwcanPrivacy.loggable(frames("10 78 20 40 01 02")))
    }

    @Test
    fun theFuelFrameKeepsOnlyItsFuelDoorByte() {
        assertEquals("10 76 40 97 08", SwcanPrivacy.loggable(frames("10 76 40 97 08 11 22 33 44 55")))
    }

    @Test
    fun storedRepliesLoseEveryQueuedFrame() {
        // After a slow stop, ATH0 is answered with the monitor's queue: a GPS frame among others.
        val reply = "10 2A A0 97 01 02 03 04\r0C 2F 60 40 01\r10 76 40 97 08 11 22\rOK\r\r>"

        assertEquals("OK\r\r>", SwcanPrivacy.redactFrames(reply))
        assertEquals("OK", ObdElmDecode.summarizeForStorage("ATH0", reply))
    }

    @Test
    fun aFrameIsFoundWhereverInTheLineItStarts() {
        for (line in listOf(
            "A6|OK|10 2A A0 97 01 02 03 04",
            "OK|10 90 C0 40 41 42",
            ">10 2A A0 97 01",
            "STOPPED 10 24 80 40 C8",
            "102AA0970102",
            "OK|102AA09701",
        )) {
            assertEquals(line, "OK", SwcanPrivacy.redactFrames("$line\rOK"))
        }
    }

    @Test
    fun aHeaderIsEnoughWhateverFollowsIt() {
        // A DLC digit, a short payload, or no payload at all: the header alone withholds the line.
        for (line in listOf(
            "10 90 C0 40 2 41 42",
            "1090C040 41 42",
            "10 90 C0 40",
            "1090C040",
            "OK|10 90 C0 40",
        )) {
            assertEquals(line, "OK", SwcanPrivacy.redactFrames("$line\rOK"))
        }
    }

    @Test
    fun hsRepliesAHeaderLongPass() {
        for (reply in listOf("41 0C 1A F8\r>", "62 28 89 01\r>", "410C1AF8\r>", "43 01 33 00\r>")) {
            assertSame(reply, SwcanPrivacy.redactFrames(reply))
        }
    }

    @Test
    fun offHsOnlyTheAdaptersOwnWordsAreKept() {
        assertEquals("OK", SwcanPrivacy.statusOnly("OK\r\r>"))
        assertEquals("A6", SwcanPrivacy.statusOnly("A6\r>"))
        assertEquals("STOPPED", SwcanPrivacy.statusOnly("STOPPED\r>"))
        assertEquals("CAN ERROR", SwcanPrivacy.statusOnly("CAN ERROR\r>"))
        assertEquals("OK|OK|A6", SwcanPrivacy.statusOnly("OK|OK|A6\r>"))
        // A frame, or a fragment too short to tell from anything else, is withheld whole.
        assertEquals("[withheld] OK", SwcanPrivacy.statusOnly("10 24 80 40 C8\rOK\r>"))
        assertEquals("OK|[withheld]", SwcanPrivacy.statusOnly("OK|10 90 C0\r>"))
        assertEquals("[withheld]", SwcanPrivacy.statusOnly("C8 7B"))
        assertEquals("", SwcanPrivacy.statusOnly(null))
    }

    @Test
    fun statusWordsAreTheAdaptersOwn() {
        assertEquals("OK", SwcanPrivacy.statusWord(" OK "))
        assertEquals("?", SwcanPrivacy.statusWord(">?"))
        assertEquals("BUFFER FULL", SwcanPrivacy.statusWord("BUFFER FULL"))
        assertEquals("<RX ERROR", SwcanPrivacy.statusWord("<RX ERROR"))
        assertEquals("STN2255 v5.10.3", SwcanPrivacy.statusWord("STN2255 v5.10.3"))
        assertEquals("", SwcanPrivacy.statusWord(">"))
        assertEquals(null, SwcanPrivacy.statusWord("C8"))
        assertEquals(null, SwcanPrivacy.statusWord("10 24"))
        assertEquals(null, SwcanPrivacy.statusWord("OK 10"))
    }

    @Test
    fun theAllowlistHoldsNothingSensitive() {
        assertTrue(SwcanPrivacy.PAYLOAD_PIDS.intersect(SwcanPrivacy.SENSITIVE_PIDS).isEmpty())
        assertTrue(SwcanPrivacy.PAYLOAD_PIDS.containsAll(SwcanPrivacy.PAYLOAD_BYTES.keys))
        // The DBC's key-store, passphrase and Wi-Fi messages are sensitive even unheard.
        assertTrue(SwcanPrivacy.SENSITIVE_PIDS.containsAll(listOf(0x148, 0x150, 0x486, 0x487, 0x488)))
    }

    @Test
    fun hsRepliesPassUntouched() {
        for (reply in listOf(
            "41 0C 1A F8\r\r>",
            "7E8 06 41 0D 00\r>",
            "410D00\r>",
            "62 43 7A 0F 12\r>",
            "49 02 01 31 47 31\r>",
        )) {
            assertSame(reply, SwcanPrivacy.redactFrames(reply))
        }
        assertEquals(null, SwcanPrivacy.redactFrames(null))
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
