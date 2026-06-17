package com.volttracker.obdpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [ObdElmDecode.isNoDataResponse], the classifier the live-poll negative-PID cache
 * and the asleep-bus detector rely on. The contract is deliberately narrow: only a literal "NO DATA"
 * or a payload-free reply counts, so a transient bus error can never disable a supported PID.
 */
class ObdElmDecodeNoDataTest {
    @Test
    fun noDataAndPayloadFreeRepliesAreNoData() {
        assertTrue(ObdElmDecode.isNoDataResponse("NO DATA"))
        assertTrue("case + prompt + CR must not matter", ObdElmDecode.isNoDataResponse("no data\r>"))
        assertTrue("prompt only is nothing usable", ObdElmDecode.isNoDataResponse(">"))
        assertTrue("empty string is nothing usable", ObdElmDecode.isNoDataResponse(""))
        assertTrue("null is nothing usable", ObdElmDecode.isNoDataResponse(null))
        assertTrue("'?' (unknown command) carries no payload", ObdElmDecode.isNoDataResponse("?"))
    }

    @Test
    fun decodedFramesAndTransientErrorsAreNotNoData() {
        assertFalse("a mode-01 frame has data", ObdElmDecode.isNoDataResponse("41 0D 28\r>"))
        assertFalse("a mode-22 frame has data", ObdElmDecode.isNoDataResponse("62 24 29 58 06"))
        // Transient bus frames carry hex-ish letters and must stay supported — a momentary hiccup
        // must never retire a PID that actually answers.
        assertFalse("CAN ERROR is transient, not unsupported", ObdElmDecode.isNoDataResponse("CAN ERROR"))
        assertFalse("STOPPED is transient", ObdElmDecode.isNoDataResponse("STOPPED"))
        assertFalse("BUFFER FULL is transient", ObdElmDecode.isNoDataResponse("BUFFER FULL"))
    }
}
