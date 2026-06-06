package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the probe lists in [ObdProbes] against the class of bug that has cost real car-test trips:
 * a malformed PID string, or a PID added to a probe array with no matching decoder. Each probe the
 * app sends is checked for shape and, for the Volt mode-22 PIDs, that [ObdProtocol] actually decodes
 * it.
 */
class ObdProbeConfigTest {
    @Test
    fun voltProbesAreWellFormedModeTwentyTwoRequests() {
        // Most mode-22 requests are "22" + a 2-byte PID. Some GM enhanced requests include a
        // trailing selector byte (e.g. 22119F01) after the PID.
        for (probe in allVoltProbes()) {
            assertTrue(
                "malformed mode-22 probe: $probe",
                probe.matches(Regex("^22[0-9A-F]{4}([0-9A-F]{2})?$")),
            )
        }
    }

    @Test
    fun everyVoltProbeHasAMatchingDecoder() {
        // Build a minimal valid positive response ("62" + PID + two zero data bytes) and
        // require ObdProtocol to decode it. A probe with no decoder branch fails here.
        for (probe in allVoltProbes()) {
            val response = "62" + probe.substring(2) + "0000"
            val parsed: ParsedPidValue? = ObdProtocol.parseKnownValue(probe, response)
            assertNotNull("no decoder for Volt probe $probe", parsed)
        }
    }

    @Test
    fun liveProbesAreAtCommandsOrModeOnePids() {
        for (probe in ObdProbes.LIVE_PROBES) {
            assertTrue(
                "malformed live probe: $probe",
                probe.matches(Regex("^AT[A-Z0-9]+$")) || probe.matches(Regex("^01[0-9A-F]{2}$")),
            )
        }
    }

    @Test
    fun tpmsDiscoveryProbesAreWellFormedModeTwentyTwoRequests() {
        for (probe in ObdProbes.TPMS_7E0_DISCOVERY_PROBES) {
            assertTrue("malformed TPMS 7E0 probe: $probe", probe.matches(Regex("^22[0-9A-F]{4}$")))
        }
        for (probe in ObdProbes.TPMS_760_DISCOVERY_PROBES) {
            assertTrue("malformed TPMS 760 probe: $probe", probe.matches(Regex("^22[0-9A-F]{4}$")))
        }
    }

    @Test
    fun tpmsDiscoveryIncludesKnownChevroletCandidates() {
        assertTrue(
            "front-left tire-pressure candidate missing",
            contains(ObdProbes.TPMS_7E0_DISCOVERY_PROBES, "22248E"),
        )
        assertTrue(
            "grouped tire-pressure candidate missing",
            contains(ObdProbes.TPMS_7E0_DISCOVERY_PROBES, "22C901"),
        )
        assertTrue(
            "TPMS receiver slot candidate missing",
            contains(ObdProbes.TPMS_760_DISCOVERY_PROBES, "224051"),
        )
    }

    @Test
    fun protocolProbesAreAtspCommands() {
        for (probe in ObdProbes.PROTOCOL_PROBES) {
            assertTrue("malformed protocol probe: $probe", probe.matches(Regex("^ATSP[0-9]$")))
        }
    }

    @Test
    fun capabilityProbesAreModeOnePids() {
        for (probe in ObdProbes.CAPABILITY_PROBES) {
            assertTrue("malformed capability probe: $probe", probe.matches(Regex("^01[0-9A-F]{2}$")))
        }
    }

    @Test
    fun correctedPackVoltagePidIsConfigured() {
        // Regression marker: HV pack voltage is 222429 (signed/64). 222885 is MGA motor
        // voltage and was used by mistake in an earlier pass.
        assertTrue(
            "HV pack voltage PID 222429 missing",
            contains(ObdProbes.VOLT_7E1_PROBES, "222429"),
        )
        assertTrue(
            "standard SOC PID 015B missing from live probes",
            contains(ObdProbes.LIVE_PROBES, "015B"),
        )
        assertTrue(
            "validated raw SOC PID 2243AF missing",
            contains(ObdProbes.VOLT_7E4_PROBES, "2243AF"),
        )
        assertTrue(
            "validated displayed SOC PID 228334 missing",
            contains(ObdProbes.VOLT_7E4_PROBES, "228334"),
        )
        assertTrue(
            "motor A current PID 222883 missing",
            contains(ObdProbes.VOLT_7E1_PROBES, "222883"),
        )
        assertTrue(
            "battery coolant pump PID 2241B2 missing",
            contains(ObdProbes.VOLT_7E4_PROBES, "2241B2"),
        )
        assertTrue(
            "engine oil life PID 22119F missing",
            contains(ObdProbes.VOLT_7E0_PROBES, "22119F"),
        )
        assertTrue(
            "engine oil life selector PID 22119F01 missing",
            contains(ObdProbes.VOLT_7E0_PROBES, "22119F01"),
        )
        assertTrue(
            "engine oil temperature PID 221154 missing",
            contains(ObdProbes.VOLT_7E0_PROBES, "221154"),
        )
        assertTrue(
            "transmission temperature PID 221940 missing",
            contains(ObdProbes.VOLT_7E2_PROBES, "221940"),
        )
        assertTrue(
            "transmission temperature selector PID 22194001 missing",
            contains(ObdProbes.VOLT_7E2_PROBES, "22194001"),
        )
    }

    private companion object {
        private fun allVoltProbes(): Array<String> =
            ObdProbes.VOLT_7E0_PROBES +
                ObdProbes.VOLT_7E1_PROBES +
                ObdProbes.VOLT_7E2_PROBES +
                ObdProbes.VOLT_7E4_PROBES

        private fun contains(
            values: Array<String>,
            target: String,
        ): Boolean = values.any { target == it }
    }
}
