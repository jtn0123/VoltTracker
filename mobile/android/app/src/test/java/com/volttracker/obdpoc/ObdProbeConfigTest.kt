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
        // Build a minimal valid positive response ("62" + PID + plausible data bytes) and
        // require ObdProtocol to decode it. A probe with no decoder branch fails here.
        for (probe in allVoltProbes()) {
            val response = samplePositiveResponse(probe)
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
    fun rejectedTpmsCandidatesAreNotReprobedByTheBroadScan() {
        assertTrue(
            "TPMS 7E0 probes were already rejected on this car; keep the executable list empty",
            ObdProbes.TPMS_7E0_DISCOVERY_PROBES.isEmpty(),
        )
        assertTrue(
            "TPMS 760 probes were already rejected on this car; keep the executable list empty",
            ObdProbes.TPMS_760_DISCOVERY_PROBES.isEmpty(),
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
            "standard EVAP vapor pressure PID 0132 missing from scan probes",
            contains(ObdProbes.LIVE_PROBES, "0132"),
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
        assertTrue(
            "HV battery capacity PID 2241A3 missing",
            contains(ObdProbes.VOLT_7E4_PROBES, "2241A3"),
        )
        assertTrue(
            "brake pedal PID 224501 missing",
            contains(ObdProbes.VOLT_7E6_PROBES, "224501"),
        )
        assertTrue(
            "BECM cell-interface probe 224181 missing",
            contains(ObdProbes.VOLT_7E7_LAYOUT_PROBES, "224181"),
        )
    }

    private companion object {
        private fun allVoltProbes(): Array<String> =
            ObdProbes.VOLT_7E0_PROBES +
                ObdProbes.VOLT_7E1_PROBES +
                ObdProbes.VOLT_7E2_PROBES +
                ObdProbes.VOLT_7E4_PROBES +
                ObdProbes.VOLT_7E6_PROBES +
                ObdProbes.VOLT_7E7_LAYOUT_PROBES

        private fun samplePositiveResponse(probe: String): String {
            val data =
                when (probe) {
                    "2241A3" -> "0205" // 51.7 Ah
                    "2245F9" -> "1432" // 51.70 Ah
                    "224329", "22432B", "224181", "224182", "22419F", "2241E0", "224200", "224201",
                    "224240", "22C218",
                    -> "BD6F" // ~3.7 V
                    "22432A", "22432C", "22434B", "22434C" -> "20"
                    "22435F", "22433F" -> "80"
                    "2240E9" -> "0014"
                    "22433B", "22433C" -> "02A5"
                    "224349", "22434A", "221C43", "2241A4", "221C26", "221C28", "221C2A", "2228CB",
                    "2240D7", "2240D9", "2240DB", "2240DD", "2240DF", "2240E1",
                    -> "40"
                    "2243A6" -> "28"
                    "2241EC" -> "2710"
                    "22437E", "2240D4" -> "00C8"
                    "221141", "221C47" -> "8C"
                    "22242C" -> "03E8"
                    "2224B0" -> "08"
                    "224501", "224502" -> "04D2"
                    else -> "0000"
                }
            return "62" + probe.substring(2) + data
        }

        private fun contains(
            values: Array<String>,
            target: String,
        ): Boolean = values.any { target == it }
    }
}
