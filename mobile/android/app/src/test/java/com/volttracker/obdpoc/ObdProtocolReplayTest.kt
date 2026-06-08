package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replay-style parser coverage for whole OBD transcript slices, not single isolated frames. */
class ObdProtocolReplayTest {
    @Test
    fun driveTranscriptReplaysIntoExpectedTelemetryValues() {
        // Real ELM transcript shape: prompt characters, SEARCHING noise, and one multi-PID
        // Mode-01 response followed by Volt-specific Mode-22 frames.
        val mode01 = "SEARCHING...\r41 0D 35 41 0C 0F A0 41 04 80 41 11 54 41 49 40\r\r>"
        val frames =
            listOf(
                Frame("ATRV", "14.1V\r>"),
                Frame("010D", mode01),
                Frame("010C", mode01),
                Frame("0104", mode01),
                Frame("0111", mode01),
                Frame("0149", mode01),
                Frame("015B", "41 5B A3\r>"),
                Frame("222429", "62 24 29 58 06\r>"),
                Frame("222414", "62 24 14 00 4E\r>"),
                Frame("22434F", "62 43 4F 43\r>"),
            )

        assertEquals(14.1f, ObdProtocol.parseVoltage(response(frames, "ATRV"))!!, 0.001f)
        assertEquals(53, ObdProtocol.parseSpeedKph(response(frames, "010D")))
        assertEquals(1000f, ObdProtocol.parseRpm(response(frames, "010C"))!!, 0.01f)
        assertEquals(50, ObdProtocol.parseEngineLoadPct(response(frames, "0104")))
        assertEquals(33, ObdProtocol.parseThrottlePct(response(frames, "0111")))
        assertEquals(25, ObdProtocol.parseAccelPedalPct(response(frames, "0149")))
        assertEquals(64, ObdProtocol.parseStateOfChargePct(response(frames, "015B")))

        val packVoltage = ObdProtocol.parseKnownValue("222429", response(frames, "222429"))
        assertNotNull(packVoltage)
        assertEquals(352.1, packVoltage!!.valueNumeric!!, 0.1)
        val packCurrent = ObdProtocol.parseKnownValue("222414", response(frames, "222414"))
        assertNotNull(packCurrent)
        assertEquals(3.9, packCurrent!!.valueNumeric!!, 0.01)
        assertEquals(
            1.37,
            ObdProtocol.parsePackPowerKw(response(frames, "222429"), response(frames, "222414"))!!,
            0.01,
        )

        val batteryTemp = ObdProtocol.parseKnownValue("22434F", response(frames, "22434F"))
        assertNotNull(batteryTemp)
        assertEquals(27.0, batteryTemp!!.valueNumeric!!, 0.01)
    }

    @Test
    fun chargeTransitionTranscriptRejectsSpeedSentinelButKeepsPackPower() {
        val frames =
            listOf(
                Frame("010D", "41 0D FF\r>"),
                Frame("222429", "62 24 29 5A 00\r>"),
                Frame("222414", "62 24 14 FD A8\r>"),
            )

        assertNull(ObdProtocol.parseSpeedKph(response(frames, "010D")))
        assertTrue(ObdProtocol.hasMaxSpeedSentinel(response(frames, "010D")))
        assertEquals(
            -10.8,
            ObdProtocol.parsePackPowerKw(response(frames, "222429"), response(frames, "222414"))!!,
            0.01,
        )
    }

    @Test
    fun diagnosticTranscriptReplaysStoredAndContinuationCodes() {
        val stored = "7E8 10 08 43 01 33 25 A2\r7E8 21 C0 73 00 00 00 00\r>"

        val codes = ObdProtocol.parseDiagnosticTroubleCodes("03", stored, "7E8")

        assertEquals(3, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("U0073", codes[2].code)
        assertEquals("stored", codes[0].status)
        assertEquals("header-7E8", codes[0].moduleKey)
        assertTrue(codes[0].rawResponse.contains("7E8 10 08"))
    }

    // ---- B1: ISO-TP multi-frame DTC reassembly across the three CAN addressing formats ------
    //
    // All frames below are SPEC-SYNTHESISED, not real car captures — the repo holds no real
    // multi-DTC ISO-TP capture (only single-/two-frame synthetic vectors). They are built to the
    // ISO 15765-2 (ISO-TP) framing rules so the parser's frame reassembly is pinned for every
    // addressing format the production code (`continuationPayload`) targets:
    //   * 11-bit CAN  : ELM prints the 3-hex CAN ID (e.g. "7E8") then the data bytes.
    //   * 29-bit CAN  : ELM prints the 4-byte (8-hex) extended header (e.g. "18 DA F1 10") then data.
    //   * headers off : ELM (ATH0) prints only the data bytes, no CAN ID at all.
    // ISO-TP PCI (first data byte after the header):
    //   * First Frame (FF)       : "1L LL" — high nibble 1, then a 12-bit total length.
    //   * Consecutive Frame (CF) : "2N"    — high nibble 2, low nibble = sequence number 0..F.
    // The marker "43" (mode-03 positive reply) lives in the FF, so the FF is decoded by the main
    // loop and only CFs flow through `continuationPayload`.
    // DTC decode reference (decodeDtc): 0x0133->P0133, 0x25A2->P25A2, 0xC073->U0073,
    //   0x1842->P1842, 0x7311->C3311.

    @Test
    fun singleFrameDtcResponseDecodesBothCodes() {
        // 11-bit single frame (SF, PCI high nibble 0). One CAN frame carries everything:
        //   7E8 | 06 | 43 | 01 33 | 25 A2 | 00 00
        //   ^ID   ^SF len=6  ^mode  ^DTC1   ^DTC2   ^padding (00 00 -> skipped)
        val codes = ObdProtocol.parseDiagnosticTroubleCodes("03", "7E8 06 43 01 33 25 A2 00 00\r>", "7E8")

        assertEquals(2, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("header-7E8", codes[0].moduleKey)
    }

    @Test
    fun multiFrameDtc11BitWithAdversarialConsecutivePayload() {
        // 11-bit FF + CF where the CF DATA deliberately starts with 0x73 (a "7x" byte) and also
        // contains 0x18 — the exact bytes the per-line sniff in `continuationPayload` keys on for
        // 11-bit / 29-bit headers. Correct reassembly must ignore payload content and slice using
        // the CAN-ID-prefix offset only.
        //   FF: 7E8 | 10 0A | 43 | 01 33 | 25 A2     (FF, total ISO-TP length 0x00A=10 bytes)
        //   CF: 7E8 | 21    | 73 11 | 18 42 | 00 00  (CF seq 1; payload bytes 73 11 18 42, then pad)
        // DTCs: FF -> P0133, P25A2 ; CF -> 0x7311=C3311, 0x1842=P1842 (0x0000 padding skipped).
        val response = "7E8 10 0A 43 01 33 25 A2\r7E8 21 73 11 18 42 00 00\r>"

        val codes = ObdProtocol.parseDiagnosticTroubleCodes("03", response, "7E8")

        assertEquals(4, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("C3311", codes[2].code)
        assertEquals("P1842", codes[3].code)
    }

    @Test
    fun multiFrameDtc29BitExtendedWithAdversarialConsecutivePayload() {
        // 29-bit extended addressing: 8-hex header "18 DA F1 10" precedes every frame. The CF
        // payload deliberately STARTS with 0x18 — colliding with the "18" extended-header sniff —
        // to prove reassembly slices after header(8)+PCI(2), not at the payload's leading bytes.
        //   FF: 18 DA F1 10 | 10 08 | 43 | 01 33 | 25 A2 | 00     (FF, total length 0x008=8)
        //   CF: 18 DA F1 10 | 21    | 18 42 | C0 73 | 00 00 00     (CF seq 1; payload 18 42 C0 73 ...)
        // DTCs: FF -> P0133, P25A2 ; CF -> 0x1842=P1842, 0xC073=U0073 (trailing 00s skipped).
        val response =
            "18 DA F1 10 10 08 43 01 33 25 A2 00\r18 DA F1 10 21 18 42 C0 73 00 00 00\r>"

        val codes = ObdProtocol.parseDiagnosticTroubleCodes("03", response, "18DAF110")

        assertEquals(4, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("P1842", codes[2].code)
        assertEquals("U0073", codes[3].code)
        assertEquals("header-18DAF110", codes[0].moduleKey)
    }

    @Test
    fun multiFrameDtcHeadersOffWithAdversarialConsecutivePayload() {
        // Headers-off (ATH0): no CAN ID is printed, so each line is just PCI + data. The CF payload
        // starts with 0x73 ("7x"); with no CAN ID the sniff must NOT mistake it for an 11-bit header.
        //   FF: 10 08 | 43 | 01 33 | 25 A2 | 00       (FF, total length 0x008=8)
        //   CF: 21    | 73 11 | 00 00 00 00 00        (CF seq 1; payload 73 11, then pad)
        // DTCs: FF -> P0133, P25A2 ; CF -> 0x7311=C3311 (trailing 00s skipped).
        val response = "10 08 43 01 33 25 A2 00\r21 73 11 00 00 00 00 00\r>"

        val codes = ObdProtocol.parseDiagnosticTroubleCodes("03", response, "")

        assertEquals(3, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("C3311", codes[2].code)
        assertEquals("generic-obd", codes[0].moduleKey)
    }

    @Test
    fun multiFramePendingDtcMode07ReassemblesAcrossFrames() {
        // Same reassembly path for mode 07 (pending codes -> marker "47"). 11-bit FF + CF.
        //   FF: 7E8 | 10 08 | 47 | 01 33 | 25 A2          (FF, total length 8)
        //   CF: 7E8 | 21    | C0 73 | 00 00 00 00         (CF seq 1; payload C0 73, then pad)
        // DTCs: P0133, P25A2 (FF) ; U0073 (CF), all flagged "pending".
        val response = "7E8 10 08 47 01 33 25 A2\r7E8 21 C0 73 00 00 00 00\r>"

        val codes = ObdProtocol.parseDiagnosticTroubleCodes("07", response, "7E8")

        assertEquals(3, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("U0073", codes[2].code)
        assertEquals("pending", codes[0].status)
    }

    @Test
    fun weakAdapterTranscriptReplaysAsMissingValuesNotExceptions() {
        val frames =
            listOf(
                Frame("010D", "SEARCHING...\rNO DATA\r>"),
                Frame("010C", "BUS INIT: ...ERROR\r>"),
                Frame("222429", "CAN ERROR\r>"),
                Frame("222414", "STOPPED\r>"),
            )

        assertNull(ObdProtocol.parseSpeedKph(response(frames, "010D")))
        assertNull(ObdProtocol.parseRpm(response(frames, "010C")))
        assertNull(ObdProtocol.parseKnownValue("222429", response(frames, "222429")))
        assertNull(
            ObdProtocol.parsePackPowerKw(response(frames, "222429"), response(frames, "222414")),
        )
        assertFalse(ObdProtocol.hasMaxSpeedSentinel(response(frames, "010D")))
    }

    private fun response(
        frames: List<Frame>,
        command: String,
    ): String {
        for (frame in frames) {
            if (frame.command == command) {
                return frame.response
            }
        }
        return ""
    }

    private class Frame(
        val command: String,
        val response: String,
    )
}
