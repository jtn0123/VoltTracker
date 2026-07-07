package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Covers the pure OBD/ELM helper contracts that feed status, storage, and dashboard labels. */
class ObdElmDecodeContractTest {
    @Test
    fun hasElmPromptOnlyRequiresPromptCharacter() {
        assertTrue(ObdElmDecode.hasElmPrompt("41 0D 28\r>"))
        assertTrue(ObdElmDecode.hasElmPrompt(">"))
        assertFalse(ObdElmDecode.hasElmPrompt("41 0D 28"))
        assertFalse(ObdElmDecode.hasElmPrompt(null))
    }

    @Test
    fun summarizeForStorageRedactsVinResponsesOnly() {
        val redacted = ObdElmDecode.summarizeForStorage(" 0902 ", "49 02 01 57 30 4C 41 42 43 44 45 46\r>")

        assertTrue(redacted.startsWith("[VIN redacted; responseLength="))
        assertEquals("", ObdElmDecode.summarizeForStorage("0902", "\r>"))
        assertEquals("41 0D 28", ObdElmDecode.summarizeForStorage("010D", "41 0D 28\r>"))
    }

    @Test
    fun appendRawUsesStorageSummaryAndKeepsVinRedacted() {
        val raw = ObdElmDecode.appendRaw("", "0902", "49 02 01 57 30 4C 41 42 43 44 45 46\r>")

        assertTrue(raw.startsWith("0902: [VIN redacted; responseLength="))
        assertTrue(raw.endsWith("\n"))
    }

    @Test
    fun pidForCommandExtractsModeSpecificPidParts() {
        assertEquals("", ObdElmDecode.pidForCommand(null))
        assertEquals("0D", ObdElmDecode.pidForCommand(" 010d "))
        assertEquals("119F", ObdElmDecode.pidForCommand("22119f"))
        assertEquals("02", ObdElmDecode.pidForCommand("0902"))
        assertEquals("", ObdElmDecode.pidForCommand("AT RV"))
        assertEquals("", ObdElmDecode.pidForCommand("01"))
        assertEquals("", ObdElmDecode.pidForCommand("22AB"))
    }

    @Test
    fun knownCommandsResolveToDashboardLabels() {
        for ((command, expected) in KNOWN_LABELS) {
            assertEquals("label for $command", expected, ObdElmDecode.nameForCommand(command.lowercase()))
        }
    }

    @Test
    fun unknownOrMissingCommandHasNoDashboardLabel() {
        assertEquals("", ObdElmDecode.nameForCommand(null))
        assertEquals("", ObdElmDecode.nameForCommand(""))
        assertEquals("", ObdElmDecode.nameForCommand("9999"))
    }

    @Test
    fun appendProbeLineWritesBlankForNullValue() {
        val raw = StringBuilder()

        ObdElmDecode.appendProbeLine(raw, "adapter", "OBDLink")
        ObdElmDecode.appendProbeLine(raw, "vin", null)

        assertEquals("adapter: OBDLink\nvin: \n", raw.toString())
    }

    @Test
    fun tailKeepsNullShortAndTrailingSegments() {
        assertSame(null, ObdElmDecode.tail(null, 8))
        assertEquals("short", ObdElmDecode.tail("short", 8))
        assertEquals("cdef", ObdElmDecode.tail("abcdef", 4))
    }

    @Test
    fun finishStatusMapsRuntimeStateToStoredSessionStatus() {
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("error"))
        assertEquals(ObdLocalStore.STATUS_ERROR, ObdElmDecode.finishStatusFor("blocked"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("connected"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scanning"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, ObdElmDecode.finishStatusFor("scan-complete"))
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor("ready"))
        assertEquals(ObdLocalStore.STATUS_DISCONNECTED, ObdElmDecode.finishStatusFor(null))
    }

    @Test
    fun friendlyConnectionMessageClassifiesCommonFailures() {
        assertTrue(
            ObdElmDecode
                .friendlyConnectionMessage(IOException("socket might closed or timeout"))
                .contains("Adapter serial channel did not open"),
        )
        assertTrue(
            ObdElmDecode
                .friendlyConnectionMessage(IOException("Permission denied"))
                .contains("Bluetooth permission is missing"),
        )
        assertEquals(
            "OBD connection failed: IOException",
            ObdElmDecode.friendlyConnectionMessage(IOException()),
        )
        assertEquals(
            " custom detail ",
            ObdElmDecode.safeMessage(IllegalStateException(" custom detail ")),
        )
    }

    private companion object {
        private val KNOWN_LABELS =
            linkedMapOf(
                "ATRV" to "adapter voltage",
                "010D" to "vehicle speed",
                "010C" to "engine rpm",
                "0105" to "coolant temperature",
                "010F" to "intake air temperature",
                "0104" to "engine load",
                "0111" to "throttle position",
                "0149" to "accelerator pedal position",
                "0142" to "control module voltage",
                "011F" to "engine run time",
                "01A6" to "odometer",
                "012F" to "fuel level",
                "015C" to "engine oil temperature",
                "22119F" to "engine oil life",
                "22119F01" to "engine oil life",
                "221154" to "engine oil temperature",
                "015B" to "state of charge",
                "222429" to "hv pack voltage",
                "222414" to "hv pack current",
                "222883" to "motor A current",
                "222884" to "motor B current",
                "222885" to "motor A voltage",
                "222886" to "motor B voltage",
                "222487" to "ev distance this cycle",
                "222889" to "prndl state",
                "221940" to "transmission temperature",
                "22194001" to "transmission temperature",
                "22434F" to "hv battery temperature",
                "224368" to "charger ac voltage",
                "224369" to "charger ac current",
                "22436B" to "charger hv voltage",
                "22436C" to "charger hv current",
                "224373" to "charging mode",
                "22437D" to "last charge energy",
                "2243A5" to "hv battery charge count",
                "2243AF" to "hv battery raw soc",
                "2241A3" to "hv battery capacity",
                "2245F9" to "hv battery capacity fallback",
                "224329" to "minimum cell voltage",
                "22432A" to "minimum cell number",
                "22432B" to "maximum cell voltage",
                "22432C" to "maximum cell number",
                "22435F" to "SOC variation",
                "2240E9" to "pack resistance",
                "22433B" to "minimum pack voltage",
                "22433C" to "maximum pack voltage",
                "224349" to "hv battery max temperature",
                "22434A" to "hv battery min temperature",
                "22434B" to "hv battery max-temp module",
                "22434C" to "hv battery min-temp module",
                "221C43" to "power electronics coolant loop temperature",
                "2241A4" to "battery coolant temperature",
                "22433F" to "minimum SOC limit",
                "2241B0" to "APM output power",
                "22437E" to "APM output current",
                "2243A6" to "HV isolation resistance (kOhm)",
                "2241EC" to "HV isolation resistance (ohm)",
                "2241B1" to "AC compressor commanded power",
                "2241B3" to "cabin heater commanded power",
                "2241B5" to "battery heater commanded power",
                "2282B5" to "AC compressor speed",
                "2282B7" to "AC compressor power",
                "224531" to "charging level",
                "228334" to "hv battery displayed soc",
                "2241B2" to "battery coolant pump rpm",
                "2241B4" to "battery coolant valve",
                "2241B6" to "battery heater power",
                "22801E" to "outside air temperature raw",
                "22801F" to "outside air temperature filtered",
                "221141" to "ignition / 12V voltage",
                "221C47" to "14V setpoint voltage",
                "221C26" to "inverter temperature 1",
                "221C28" to "inverter temperature 2",
                "221C2A" to "inverter temperature 3",
                "2228CB" to "motor temperature",
                "22242C" to "brake torque demand",
                "2224B0" to "regen braking active",
                "224501" to "brake pedal position 1",
                "224502" to "brake pedal position 2",
                "2240D7" to "battery section 1 temperature",
                "2240D9" to "battery section 2 temperature",
                "2240DB" to "battery section 3 temperature",
                "2240DD" to "battery section 4 temperature",
                "2240DF" to "battery section 5 temperature",
                "2240E1" to "battery section 6 temperature",
                "2240D4" to "HD pack current",
                "22C218" to "average cell voltage",
                "0132" to "EVAP vapor pressure",
                "22248E" to "candidate tire pressure front-left",
                "22248F" to "candidate tire pressure front-right",
                "222490" to "candidate tire pressure rear-right",
                "222491" to "candidate tire pressure rear-left",
                "22C901" to "candidate tire pressures",
                "22C902" to "candidate tire temperatures",
                "224051" to "candidate tire receiver slot 1",
                "224052" to "candidate tire receiver slot 2",
                "224053" to "candidate tire receiver slot 3",
                "224054" to "candidate tire receiver slot 4",
                "0902" to "vin",
            )
    }
}
