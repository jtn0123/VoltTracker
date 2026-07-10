package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Deterministic parser fuzzing for the adapter boundary. Real ELM clones interleave headers,
 * prompts, text diagnostics, partial frames, and arbitrary bytes; none of that input may crash the
 * polling thread or produce non-finite numeric values. The fixed seed makes every failure exactly
 * reproducible in CI.
 */
class ObdProtocolFuzzTest {
    @Test
    fun arbitraryAdapterTextNeverThrowsOrProducesNonFiniteValues() {
        val random = Random(FUZZ_SEED)
        repeat(FUZZ_CASES) { iteration ->
            val response =
                if (iteration % 97 ==
                    0
                ) {
                    null
                } else {
                    randomAdapterText(random, random.nextInt(MAX_INPUT_CHARS + 1))
                }
            val command = if (iteration % 89 == 0) null else commands[random.nextInt(commands.size)]
            try {
                assertFinite(ObdProtocol.parseSpeedKph(response)?.toDouble())
                assertFinite(ObdProtocol.parseRpm(response)?.toDouble())
                assertFinite(ObdProtocol.parseCoolantC(response)?.toDouble())
                assertFinite(ObdProtocol.parseEngineLoadPct(response)?.toDouble())
                assertFinite(ObdProtocol.parseThrottlePct(response)?.toDouble())
                assertFinite(ObdProtocol.parseAccelPedalPct(response)?.toDouble())
                assertFinite(ObdProtocol.parseStateOfChargePct(response)?.toDouble())
                assertFinite(ObdProtocol.parseVoltage(response)?.toDouble())
                assertFinite(ObdProtocol.parseKnownValue(command, response)?.valueNumeric)
                assertFinite(ObdProtocol.parseCellVoltageProbe(command, response)?.valueNumeric)
                assertFinite(ObdProtocol.parsePackPowerKw(response, response))

                val dtcs =
                    ObdProtocol.parseDiagnosticTroubleCodes(
                        dtcCommands[iteration % dtcCommands.size],
                        response,
                        response,
                    )
                for (dtc in dtcs) {
                    assertTrue("invalid DTC shape at iteration $iteration: ${dtc.code}", DTC_PATTERN.matches(dtc.code))
                }
                ObdProtocol.parseVin(response)?.let { vin ->
                    assertTrue("invalid VIN shape at iteration $iteration: $vin", VIN_PATTERN.matches(vin))
                }
                ObdProtocol.summarize(response)
                ObdProtocol.cleanSupportedPids(response)
                ObdProtocol.isBenignSentinelResponse(command, response)
                ObdProtocol.elmSegmentedHex(response)
            } catch (ex: Throwable) {
                throw AssertionError(
                    "protocol fuzz failure seed=$FUZZ_SEED iteration=$iteration command=$command input=${response?.toLogLiteral()}",
                    ex,
                )
            }
        }
    }

    @Test
    fun validMode22FramesWithRandomPayloadsStayFinite() {
        val random = Random(FUZZ_SEED xor 0x22L)
        val mode22Commands = commands.filter { it.startsWith("22") }
        repeat(FUZZ_CASES) { iteration ->
            val command = mode22Commands[iteration % mode22Commands.size]
            val payload = ByteArray(random.nextInt(32)) { random.nextInt(256).toByte() }
            val response =
                buildString {
                    append("SEARCHING...\r7E8 ")
                    append("62")
                    append(command.drop(2))
                    for (byte in payload) append("%02X".format(byte.toInt() and 0xFF))
                    append("\r>")
                }
            val parsed = ObdProtocol.parseKnownValue(command, response)
            assertFinite(parsed?.valueNumeric)
        }
    }

    private fun assertFinite(value: Double?) {
        assertTrue("decoder returned non-finite value: $value", value == null || value.isFinite())
    }

    private fun randomAdapterText(
        random: Random,
        length: Int,
    ): String =
        buildString(length) {
            repeat(length) {
                append(FUZZ_ALPHABET[random.nextInt(FUZZ_ALPHABET.length)])
            }
        }

    private fun String.toLogLiteral(): String =
        take(256)
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u0000", "\\0")

    private companion object {
        private const val FUZZ_SEED = 0x564F4C54L
        private const val FUZZ_CASES = 10_000
        private const val MAX_INPUT_CHARS = 384
        private const val FUZZ_ALPHABET = "0123456789ABCDEFabcdefXYZ!? :>\r\n\t\u0000éß"
        private val DTC_PATTERN = Regex("[PCBU][0-3][0-9A-F]{3}")
        private val VIN_PATTERN = Regex("[A-HJ-NPR-Z0-9]{17}")
        private val dtcCommands = arrayOf("03", "07", "0A", "0202")
        private val commands =
            (
                ObdProbes.LIVE_PROBES +
                    ObdProbes.VOLT_7E0_PROBES +
                    ObdProbes.VOLT_7E1_PROBES +
                    ObdProbes.VOLT_7E2_PROBES +
                    ObdProbes.VOLT_7E4_PROBES +
                    ObdProbes.VOLT_7E6_PROBES +
                    ObdProbes.VOLT_7E7_LAYOUT_PROBES +
                    arrayOf("", "garbage", "22", "010", "0902", "03")
            ).distinct()
    }
}
