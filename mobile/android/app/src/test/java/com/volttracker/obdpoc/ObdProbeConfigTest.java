package com.volttracker.obdpoc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue;
import org.junit.Test;

/**
 * Guards the probe lists in {@link ObdProbes} against the class of bug that has cost real car-test
 * trips: a malformed PID string, or a PID added to a probe array with no matching decoder. Each
 * probe the app sends is checked for shape and, for the Volt mode-22 PIDs, that {@link ObdProtocol}
 * actually decodes it.
 */
public class ObdProbeConfigTest {

    @Test
    public void voltProbesAreWellFormedModeTwentyTwoRequests() {
        // Most mode-22 requests are "22" + a 2-byte PID. Some GM enhanced requests include a
        // trailing selector byte (e.g. 22119F01) after the PID.
        for (String probe : allVoltProbes()) {
            assertTrue(
                    "malformed mode-22 probe: " + probe,
                    probe.matches("^22[0-9A-F]{4}([0-9A-F]{2})?$"));
        }
    }

    @Test
    public void everyVoltProbeHasAMatchingDecoder() {
        // Build a minimal valid positive response ("62" + PID + two zero data bytes) and
        // require ObdProtocol to decode it. A probe with no decoder branch fails here.
        for (String probe : allVoltProbes()) {
            String response = "62" + probe.substring(2) + "0000";
            ParsedPidValue parsed = ObdProtocol.parseKnownValue(probe, response);
            assertNotNull("no decoder for Volt probe " + probe, parsed);
        }
    }

    @Test
    public void liveProbesAreAtCommandsOrModeOnePids() {
        for (String probe : ObdProbes.LIVE_PROBES) {
            assertTrue(
                    "malformed live probe: " + probe,
                    probe.matches("^AT[A-Z0-9]+$") || probe.matches("^01[0-9A-F]{2}$"));
        }
    }

    @Test
    public void tpmsDiscoveryProbesAreWellFormedModeTwentyTwoRequests() {
        for (String probe : ObdProbes.TPMS_7E0_DISCOVERY_PROBES) {
            assertTrue("malformed TPMS 7E0 probe: " + probe, probe.matches("^22[0-9A-F]{4}$"));
        }
        for (String probe : ObdProbes.TPMS_760_DISCOVERY_PROBES) {
            assertTrue("malformed TPMS 760 probe: " + probe, probe.matches("^22[0-9A-F]{4}$"));
        }
    }

    @Test
    public void tpmsDiscoveryIncludesKnownChevroletCandidates() {
        assertTrue(
                "front-left tire-pressure candidate missing",
                contains(ObdProbes.TPMS_7E0_DISCOVERY_PROBES, "22248E"));
        assertTrue(
                "grouped tire-pressure candidate missing",
                contains(ObdProbes.TPMS_7E0_DISCOVERY_PROBES, "22C901"));
        assertTrue(
                "TPMS receiver slot candidate missing",
                contains(ObdProbes.TPMS_760_DISCOVERY_PROBES, "224051"));
    }

    @Test
    public void protocolProbesAreAtspCommands() {
        for (String probe : ObdProbes.PROTOCOL_PROBES) {
            assertTrue("malformed protocol probe: " + probe, probe.matches("^ATSP[0-9]$"));
        }
    }

    @Test
    public void capabilityProbesAreModeOnePids() {
        for (String probe : ObdProbes.CAPABILITY_PROBES) {
            assertTrue("malformed capability probe: " + probe, probe.matches("^01[0-9A-F]{2}$"));
        }
    }

    @Test
    public void correctedPackVoltagePidIsConfigured() {
        // Regression marker: HV pack voltage is 222429 (signed/64). 222885 is MGA motor
        // voltage and was used by mistake in an earlier pass.
        assertTrue(
                "HV pack voltage PID 222429 missing",
                contains(ObdProbes.VOLT_7E1_PROBES, "222429"));
        assertTrue(
                "standard SOC PID 015B missing from live probes",
                contains(ObdProbes.LIVE_PROBES, "015B"));
        assertTrue(
                "validated raw SOC PID 2243AF missing",
                contains(ObdProbes.VOLT_7E4_PROBES, "2243AF"));
        assertTrue(
                "validated displayed SOC PID 228334 missing",
                contains(ObdProbes.VOLT_7E4_PROBES, "228334"));
        assertTrue(
                "motor A current PID 222883 missing",
                contains(ObdProbes.VOLT_7E1_PROBES, "222883"));
        assertTrue(
                "battery coolant pump PID 2241B2 missing",
                contains(ObdProbes.VOLT_7E4_PROBES, "2241B2"));
        assertTrue(
                "engine oil life PID 22119F missing",
                contains(ObdProbes.VOLT_7E0_PROBES, "22119F"));
        assertTrue(
                "engine oil life selector PID 22119F01 missing",
                contains(ObdProbes.VOLT_7E0_PROBES, "22119F01"));
        assertTrue(
                "engine oil temperature PID 221154 missing",
                contains(ObdProbes.VOLT_7E0_PROBES, "221154"));
        assertTrue(
                "transmission temperature PID 221940 missing",
                contains(ObdProbes.VOLT_7E2_PROBES, "221940"));
        assertTrue(
                "transmission temperature selector PID 22194001 missing",
                contains(ObdProbes.VOLT_7E2_PROBES, "22194001"));
    }

    private static String[] allVoltProbes() {
        String[] a = ObdProbes.VOLT_7E0_PROBES;
        String[] b = ObdProbes.VOLT_7E1_PROBES;
        String[] c = ObdProbes.VOLT_7E2_PROBES;
        String[] d = ObdProbes.VOLT_7E4_PROBES;
        String[] all = new String[a.length + b.length + c.length + d.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);
        System.arraycopy(d, 0, all, a.length + b.length + c.length, d.length);
        return all;
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
