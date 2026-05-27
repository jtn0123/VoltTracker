package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Replay-style parser coverage for whole OBD transcript slices, not single isolated frames. */
public class ObdProtocolReplayTest {

    @Test
    public void driveTranscriptReplaysIntoExpectedTelemetryValues() {
        // Real ELM transcript shape: prompt characters, SEARCHING noise, and one multi-PID
        // Mode-01 response followed by Volt-specific Mode-22 frames.
        String mode01 = "SEARCHING...\r41 0D 35 41 0C 0F A0 41 04 80 41 11 54 41 49 40\r\r>";
        List<Frame> frames =
                List.of(
                        new Frame("ATRV", "14.1V\r>"),
                        new Frame("010D", mode01),
                        new Frame("010C", mode01),
                        new Frame("0104", mode01),
                        new Frame("0111", mode01),
                        new Frame("0149", mode01),
                        new Frame("015B", "41 5B A3\r>"),
                        new Frame("222429", "62 24 29 58 06\r>"),
                        new Frame("222414", "62 24 14 00 4E\r>"),
                        new Frame("22434F", "62 43 4F 43\r>"));

        assertEquals(14.1f, ObdProtocol.parseVoltage(response(frames, "ATRV")), 0.001f);
        assertEquals(Integer.valueOf(53), ObdProtocol.parseSpeedKph(response(frames, "010D")));
        assertEquals(1000f, ObdProtocol.parseRpm(response(frames, "010C")), 0.01f);
        assertEquals(Integer.valueOf(50), ObdProtocol.parseEngineLoadPct(response(frames, "0104")));
        assertEquals(Integer.valueOf(33), ObdProtocol.parseThrottlePct(response(frames, "0111")));
        assertEquals(Integer.valueOf(25), ObdProtocol.parseAccelPedalPct(response(frames, "0149")));
        assertEquals(
                Integer.valueOf(64), ObdProtocol.parseStateOfChargePct(response(frames, "015B")));

        ObdProtocol.ParsedPidValue packVoltage =
                ObdProtocol.parseKnownValue("222429", response(frames, "222429"));
        assertNotNull(packVoltage);
        assertEquals(352.1, packVoltage.valueNumeric, 0.1);
        ObdProtocol.ParsedPidValue packCurrent =
                ObdProtocol.parseKnownValue("222414", response(frames, "222414"));
        assertNotNull(packCurrent);
        assertEquals(3.9, packCurrent.valueNumeric, 0.01);
        assertEquals(
                1.37,
                ObdProtocol.parsePackPowerKw(
                        response(frames, "222429"), response(frames, "222414")),
                0.01);

        ObdProtocol.ParsedPidValue batteryTemp =
                ObdProtocol.parseKnownValue("22434F", response(frames, "22434F"));
        assertNotNull(batteryTemp);
        assertEquals(27.0, batteryTemp.valueNumeric, 0.01);
    }

    @Test
    public void chargeTransitionTranscriptRejectsSpeedSentinelButKeepsPackPower() {
        List<Frame> frames =
                List.of(
                        new Frame("010D", "41 0D FF\r>"),
                        new Frame("222429", "62 24 29 5A 00\r>"),
                        new Frame("222414", "62 24 14 FD A8\r>"));

        assertNull(ObdProtocol.parseSpeedKph(response(frames, "010D")));
        assertTrue(ObdProtocol.hasMaxSpeedSentinel(response(frames, "010D")));
        assertEquals(
                -10.8,
                ObdProtocol.parsePackPowerKw(
                        response(frames, "222429"), response(frames, "222414")),
                0.01);
    }

    @Test
    public void diagnosticTranscriptReplaysStoredAndContinuationCodes() {
        String stored = "7E8 10 08 43 01 33 25 A2\r7E8 21 C0 73 00 00 00 00\r>";

        List<ObdProtocol.DiagnosticTroubleCode> codes =
                ObdProtocol.parseDiagnosticTroubleCodes("03", stored, "7E8");

        assertEquals(3, codes.size());
        assertEquals("P0133", codes.get(0).code);
        assertEquals("P25A2", codes.get(1).code);
        assertEquals("U0073", codes.get(2).code);
        assertEquals("stored", codes.get(0).status);
        assertEquals("header-7E8", codes.get(0).moduleKey);
        assertTrue(codes.get(0).rawResponse.contains("7E8 10 08"));
    }

    @Test
    public void weakAdapterTranscriptReplaysAsMissingValuesNotExceptions() {
        List<Frame> frames =
                List.of(
                        new Frame("010D", "SEARCHING...\rNO DATA\r>"),
                        new Frame("010C", "BUS INIT: ...ERROR\r>"),
                        new Frame("222429", "CAN ERROR\r>"),
                        new Frame("222414", "STOPPED\r>"));

        assertNull(ObdProtocol.parseSpeedKph(response(frames, "010D")));
        assertNull(ObdProtocol.parseRpm(response(frames, "010C")));
        assertNull(ObdProtocol.parseKnownValue("222429", response(frames, "222429")));
        assertNull(
                ObdProtocol.parsePackPowerKw(
                        response(frames, "222429"), response(frames, "222414")));
        assertFalse(ObdProtocol.hasMaxSpeedSentinel(response(frames, "010D")));
    }

    private static String response(List<Frame> frames, String command) {
        for (Frame frame : frames) {
            if (frame.command.equals(command)) {
                return frame.response;
            }
        }
        return "";
    }

    private static final class Frame {
        final String command;
        final String response;

        Frame(String command, String response) {
            this.command = command;
            this.response = response;
        }
    }
}
