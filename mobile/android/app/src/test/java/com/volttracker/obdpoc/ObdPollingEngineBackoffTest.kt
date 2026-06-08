package com.volttracker.obdpoc

import com.volttracker.obdpoc.ConnectionFailureClassifier.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decision-table tests for [ObdPollingEngine.computeBackoffMs] and the helper methods that sit
 * alongside it ([ObdPollingEngine.exceptionClassName], [ObdPollingEngine.stackHead]). These run on
 * the host JVM — no Bluetooth, no Robolectric — because the methods under test are pure static
 * functions.
 */
class ObdPollingEngineBackoffTest {
    // ---- A3: adaptive retry decision table -----------------------------------------

    @Test
    fun wedgedModeOverridesExponentialBackoffOnAttemptThree() {
        // Two instant_drops have already burned attempts 1 + 2. wedgedMode flips on, and the
        // engine's *third* attempt should sleep 8 s instead of the default 8 s reconnect /
        // ~1.5 s initial-connect curve.
        val backoffMs =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                3,
                // everConnected =
                false,
                // wedgedMode =
                true,
            )
        assertEquals(
            "two instant_drops should switch to the long-backoff schedule (8 s)",
            8_000L,
            backoffMs,
        )
    }

    @Test
    fun wedgedModeUsesTwelveSecondsForFourthAttempt() {
        val backoffMs =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                4,
                // everConnected =
                false,
                // wedgedMode =
                true,
            )
        assertEquals(12_000L, backoffMs)
    }

    @Test
    fun wedgedModeFallsBackToStandardCurveAfterLongBackoffSchedule() {
        // Only the first two wedge-mode retries get the explicit long backoff; attempts 5/6 fall
        // back to the regular curves so MAX_RECONNECT_ATTEMPTS still terminates the loop.
        val wedgedFifth =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                5,
                // everConnected =
                false,
                // wedgedMode =
                true,
            )
        val standardFifth =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                5,
                // everConnected =
                false,
                // wedgedMode =
                false,
            )
        assertEquals(
            "post-schedule wedge mode must equal the standard curve so the retry budget" +
                " still terminates",
            standardFifth,
            wedgedFifth,
        )
    }

    @Test
    fun nonWedgedModeUsesReconnectCurveWhenEverConnected() {
        // Pick attempt=2 deliberately: at attempt 3 reconnectBackoffMs already happens to be
        // 8 s by the exponential ramp, which would make this test indistinguishable from the
        // wedged-mode path. Attempt 2's value (4 s) is unique to the reconnect curve and
        // confirms the non-wedge code path was taken.
        val backoffMs =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                2,
                // everConnected =
                true,
                // wedgedMode =
                false,
            )
        assertEquals(ObdElmDecode.reconnectBackoffMs(2), backoffMs)
        assertTrue("expected the reconnect curve at attempt 2 (4 s)", backoffMs == 4_000L)
    }

    @Test
    fun nonWedgedModeUsesInitialConnectCurveWhenNeverConnected() {
        val backoffMs =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                3,
                // everConnected =
                false,
                // wedgedMode =
                false,
            )
        assertEquals(ObdElmDecode.initialConnectBackoffMs(3), backoffMs)
    }

    // ---- B6: exception fingerprint helpers ------------------------------------------

    @Test
    fun exceptionClassNameReturnsFullyQualifiedName() {
        assertEquals(
            "java.io.IOException",
            ObdPollingEngine.exceptionClassName(java.io.IOException("nope")),
        )
    }

    @Test
    fun exceptionClassNameOfNullIsEmpty() {
        assertEquals("", ObdPollingEngine.exceptionClassName(null))
    }

    @Test
    fun stackHeadJoinsFramesWithSeparator() {
        // fillInStackTrace runs in the test's stack frame so this hits the real Throwable path.
        val ex: Throwable = RuntimeException("boom")
        val head = ObdPollingEngine.stackHead(ex)
        assertTrue(
            "stackHead should mention this test method, got: $head",
            head.contains("stackHeadJoinsFramesWithSeparator"),
        )
        assertTrue("stackHead should join multiple frames with ' | '", head.contains(" | "))
    }

    @Test
    fun stackHeadIsCappedAtMaxChars() {
        // Build a synthetic stack trace large enough to overflow the 1000-char cap.
        val ex: Throwable = RuntimeException("boom")
        val frames =
            Array(20) { i ->
                // Each frame ~80 chars → 20 frames * 80 = 1600 chars before truncation.
                StackTraceElement(
                    "com.volttracker.obdpoc.SyntheticClassNameForTest$i",
                    "methodNameLongEnoughToMatter",
                    "ObdPollingEngineBackoffTest.java",
                    42,
                )
            }
        ex.stackTrace = frames
        val head = ObdPollingEngine.stackHead(ex)
        assertTrue(
            "stackHead must be capped at 1000 chars, got ${head.length}",
            head.length <= 1000,
        )
    }

    @Test
    fun stackHeadOfNullIsEmpty() {
        assertEquals("", ObdPollingEngine.stackHead(null))
    }

    // ---- Sanity: the classifier + backoff combo for the user's reproducer ----------

    @Test
    fun userReproducerIsClassifiedAsInstantDropAndTriggersLongBackoff() {
        // Replays the user's three failed sessions: socket opens, first read fails in ~250 ms
        // with "read ret: -1". Two of these in a row must classify and switch into long backoff.
        val firstAttempt: Input =
            ConnectionFailureClassifier.ofRaw(
                "first_read",
                250L,
                "read failed, socket might closed or timeout, read ret: -1",
                "java.io.IOException",
                true,
            )
        val secondAttempt: Input =
            ConnectionFailureClassifier.ofRaw(
                "first_read",
                300L,
                "read failed, socket might closed or timeout, read ret: -1",
                "java.io.IOException",
                true,
            )
        if (ConnectionFailureClassifier.classify(firstAttempt) != FailureClass.INSTANT_DROP) {
            fail("first attempt should be classified as INSTANT_DROP")
        }
        if (ConnectionFailureClassifier.classify(secondAttempt) != FailureClass.INSTANT_DROP) {
            fail("second attempt should be classified as INSTANT_DROP")
        }
        // With both classified instant_drops, the engine would have set wedgedMode=true. The
        // third attempt's backoff must be 8 s — the wedged-adapter long-backoff entry.
        val backoff =
            ObdPollingEngine.computeBackoffMs(
                // attempt =
                3,
                // everConnected =
                false,
                // wedgedMode =
                true,
            )
        assertEquals(8_000L, backoff)
    }
}
