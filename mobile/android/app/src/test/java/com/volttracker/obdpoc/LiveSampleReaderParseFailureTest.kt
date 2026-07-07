package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers [LiveSampleReader]'s `pid_parse_failed` diagnostic (B2): a PID we polled this cycle that
 * answers with a genuine positive ECU frame the parser still cannot decode is surfaced as a
 * rate-limited event, instead of being silently dropped and looking like an unsupported PID.
 *
 * The reader is driven directly with a [PidPollingState] backed by [ScriptedEngine], a minimal
 * [ObdPollingEngine] subclass that returns scripted raw responses per command without any
 * Bluetooth IO. Emitted events are observed through a real [SessionRecorder] writing to a temp
 * [ObdSessionLog]; the `.jsonl` lines are read back and counted — the same sink the existing
 * `speed_rejected` event flows through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveSampleReaderParseFailureTest {
    private lateinit var service: ObdService
    private lateinit var logDir: File
    private lateinit var sessionLog: ObdSessionLog
    private lateinit var pidPolling: PidPollingState
    private lateinit var engine: ScriptedEngine
    private lateinit var reader: LiveSampleReader
    private val context = CapturingContext()

    @Before
    fun setUp() {
        service = Robolectric.setupService(ObdService::class.java)
        service.activeName = "Test Adapter"
        service.sessionStartedAtMs = System.currentTimeMillis()

        logDir = File(System.getProperty("java.io.tmpdir"), "lsr-parse-${System.nanoTime()}")
        sessionLog = ObdSessionLog(logDir)
        service.recorder = SessionRecorder(Any(), sessionLog, null)
        service.recorder.openSession("obd", "AA:BB", "Test Adapter", service.sessionStartedAtMs)

        engine = ScriptedEngine(service)
        pidPolling = PidPollingState(service, engine)
        reader = LiveSampleReader(service, SpeedPlausibilityFilter(), pidPolling)
    }

    @After
    fun tearDown() {
        sessionLog.close()
        logDir.deleteRecursively()
        try {
            service.onDestroy()
        } catch (ignored: RuntimeException) {
            // The foreground service was never started in this test; teardown is best-effort.
        }
    }

    @Test
    fun firesOnceForPolledPidWithMalformedPositiveResponse() {
        // "41 0D" carries the positive Mode-01 speed marker but no data byte, so the speed parser
        // returns null even though the ECU clearly answered. This is the silent-drop bug we surface.
        engine.responses["010D"] = "41 0D\r>"

        reader.read(context)
        assertEquals(
            "a polled PID with a positive-but-unparseable frame must emit pid_parse_failed",
            1,
            countEvents("pid_parse_failed", "010D"),
        )

        // Rate limit: a persistently malformed PID must not re-log every cycle.
        reader.read(context)
        assertEquals(
            "pid_parse_failed must be rate-limited to once per command per session",
            1,
            countEvents("pid_parse_failed", "010D"),
        )
    }

    @Test
    fun doesNotFireForUnsupportedOrNotPolledPid() {
        // 010D answers NO DATA (unsupported / no answer) — it must NOT be conflated with a parse
        // failure. Everything else falls back to the prompt-only default, which is not a positive
        // ECU frame either, so no PID — polled or not — should trigger the event.
        engine.responses["010D"] = "NO DATA\r>"

        reader.read(context)

        assertEquals(
            "an unsupported (NO DATA) polled PID must not emit pid_parse_failed",
            0,
            countEvents("pid_parse_failed", "010D"),
        )
        assertEquals(
            "no PID should emit pid_parse_failed when none returned a malformed positive frame",
            0,
            countAllEvents("pid_parse_failed"),
        )
    }

    @Test
    fun speedJumpRejectedButSentinelIsTaggedAsChargeTransitionHint() {
        engine.responses["010D"] = "41 0D 00\r>"
        var sample = reader.read(context)
        assertEquals(0, sample.optInt("speedKph"))
        assertEquals(0, countAllEvents("speed_rejected"))

        engine.responses["010D"] = "41 0D C8\r>"
        sample = reader.read(context)
        assertFalse(sample.has("speedKph"))
        assertEquals(200, sample.optInt("speedRejectedKph"))
        assertEquals(1, countAllEvents("speed_rejected"))

        reader.reset()
        engine.responses["010D"] = "41 0D FF\r>"
        sample = reader.read(context)
        assertFalse(sample.has("speedKph"))
        assertEquals(255, sample.optInt("speedRejectedKph"))
        assertTrue(sample.optBoolean("chargeTransitionHint"))
        val payload = eventPayloads("speed_rejected").last()
        assertEquals("charge_transition_hint", payload.optString("reason"))
        assertEquals(
            "a benign 0xFF speed sentinel must not also be reported as a PID parse failure",
            0,
            countAllEvents("pid_parse_failed"),
        )
    }

    @Test
    fun throttleFallsBackToIceThrottleWhenAcceleratorPedalIsUnavailable() {
        engine.responses["0149"] = "NO DATA\r>"
        engine.responses["0111"] = "41 11 80\r>"

        val sample = reader.read(context)
        val nextSample = reader.read(context)

        assertFalse(sample.has("throttlePct"))
        assertEquals(50, nextSample.optInt("throttlePct"))
        assertEquals("iceThrottleBody", nextSample.optString("throttleSource"))
    }

    @Test
    fun jsonEncodingFailureReturnsEmptySample() {
        engine.responses["010D"] = "41 0D 32\r>"

        val sample = reader.read(ThrowingHealthContext())

        assertEquals(0, sample.length())
    }

    @Test
    fun richScriptedPollsPopulateStandardBatteryChargeAndEnhancedFields() {
        scriptRichResponses()

        var sample = JSONObject()
        repeat(241) {
            sample = reader.read(context)
        }

        assertEquals(241, sample.optInt("sampleCount"))
        assertEquals("obd", sample.optString("source"))
        assertEquals("Test Adapter", sample.optString("adapter"))
        assertEquals("VIN-REDACTED", sample.optString("vin"))
        assertEquals("0100,0120,0140", sample.optString("supportedPids"))
        assertEquals(14.1, sample.optDouble("voltage"), 0.01)
        assertEquals(50, sample.optInt("speedKph"))
        assertEquals(1568, sample.optInt("rpm"))
        assertEquals(59, sample.optInt("coolantC"))
        assertEquals(40, sample.optInt("intakeAirTempC"))
        assertEquals(70, sample.optInt("loadPct"))
        assertEquals(50, sample.optInt("throttlePct"))
        assertEquals("accelPedal", sample.optString("throttleSource"))
        assertEquals(50, sample.optInt("soc"))
        assertEquals(14.0, sample.optDouble("controlModuleVoltage"), 0.01)
        assertEquals(60, sample.optInt("engineRunTimeSec"))
        assertEquals(50, sample.optInt("fuelLevelPct"))
        assertEquals(50, sample.optInt("engineOilTempC"))
        assertEquals(123456.7, sample.optDouble("odometerKm"), 0.01)
        assertEquals(76712.4, sample.optDouble("odometerMiles"), 0.1)
        assertEquals(20.0, sample.optDouble("batteryTemp"), 0.01)
        assertEquals(360.0, sample.optDouble("packVoltage"), 0.01)
        assertEquals(50.0, sample.optDouble("packCurrentA"), 0.01)
        assertEquals(18.0, sample.optDouble("powerKw"), 0.01)
        assertEquals(51.7, sample.optDouble("capacityAh"), 0.01)
        assertEquals(99.4, sample.optDouble("sohPct"), 0.1)
        assertEquals(18.4, sample.optDouble("packEnergyKwh"), 0.1)
        assertEquals(53.9, sample.optDouble("hvBatteryRawSoc"), 0.1)
        assertEquals(1737, sample.optInt("hvBatteryChargeCount"))
        assertEquals(10000, sample.optInt("lastChargeEnergyWh"))
        assertEquals(3.604, sample.optDouble("minCellVoltage"), 0.001)
        assertEquals(3.625, sample.optDouble("maxCellVoltage"), 0.001)
        assertEquals(21, sample.optInt("cellBalanceMv"))
        assertEquals(32, sample.optInt("minCellNumber"))
        assertEquals(96, sample.optInt("maxCellNumber"))
        assertEquals(50.2, sample.optDouble("socVariationPct"), 0.1)
        assertEquals(390.0, sample.optDouble("chargerHvVoltage"), 0.01)
        assertEquals(8.0, sample.optDouble("chargerHvCurrent"), 0.01)
        assertEquals(3.1, sample.optDouble("chargerPowerKw"), 0.1)
        assertEquals("CHARGING", sample.optString("chargingMode"))
        assertEquals("AC_2", sample.optString("chargingLevel"))
        assertEquals(80, sample.optInt("engineOilLifePct"))
        assertEquals(50.0, sample.optDouble("motorACurrentA"), 0.01)
        assertEquals(-30.0, sample.optDouble("motorBCurrentA"), 0.01)
        assertEquals(350.0, sample.optDouble("motorAVoltage"), 0.01)
        assertEquals(300.0, sample.optDouble("motorBVoltage"), 0.01)
        assertEquals(17.5, sample.optDouble("motorAPowerKw"), 0.1)
        assertEquals(-9.0, sample.optDouble("motorBPowerKw"), 0.1)
        assertEquals(12.34, sample.optDouble("evDistanceThisCycleKm"), 0.01)
        assertEquals(50, sample.optInt("transmissionTempC"))
        assertEquals(1000, sample.optInt("batteryCoolantPumpRpm"))
        assertEquals(7, sample.optInt("batteryCoolantValveRaw"))
        assertEquals(2000, sample.optInt("batteryHeaterPowerW"))
        assertEquals(35.0, sample.optDouble("outsideTempC"), 0.01)
        assertEquals("driving_gas", sample.optString("vehicleState"))
        assertFalse(sample.optJSONArray("vehicleStateReasons")!!.isNull(0))
        assertEquals(3, sample.optJSONArray("health")!!.length())
        assertEquals(34.05, sample.optDouble("lat"), 0.001)
        assertTrue("sample should carry the current-cycle raw transcript", sample.has("raw"))
    }

    /** Counts `pid_parse_failed` event lines whose `command` payload matches [command]. */
    private fun countEvents(
        event: String,
        command: String,
    ): Int = eventPayloads(event).count { it.optString("command") == command }

    private fun countAllEvents(event: String): Int = eventPayloads(event).size

    private fun scriptRichResponses() {
        engine.responses.putAll(
            mapOf(
                "ATRV" to "14.1V\r>",
                "010D" to "41 0D 32\r>",
                "010C" to "41 0C 18 80\r>",
                "0105" to "41 05 63\r>",
                "010F" to "41 0F 50\r>",
                "0104" to "41 04 B2\r>",
                "0149" to "41 49 80\r>",
                "015B" to "41 5B 7F\r>",
                "0142" to "41 42 36 B0\r>",
                "011F" to "41 1F 00 3C\r>",
                "012F" to "41 2F 80\r>",
                "221154" to "62 11 54 5A\r>",
                "01A6" to "41 A6 00 12 D6 87\r>",
                "222429" to "62 24 29 5A 00\r>",
                "222414" to "62 24 14 03 E8\r>",
                "22434F" to "62 43 4F 3C\r>",
                "2241A3" to "62 41 A3 02 05\r>",
                "2243AF" to "7EC 05 62 43 AF 89 F9\r>",
                "2243A5" to "7EC 05 62 43 A5 06 C9\r>",
                "22437D" to "62 43 7D 03 E8\r>",
                "224329" to "62 43 29 16 87\r>",
                "22432A" to "62 43 2A 20\r>",
                "22432B" to "62 43 2B 16 A8\r>",
                "22432C" to "62 43 2C 60\r>",
                "22435F" to "62 43 5F 80\r>",
                "22436B" to "62 43 6B 03 0C\r>",
                "22436C" to "62 43 6C 00 A0\r>",
                "224373" to "7EC 05 62 43 73 FF FF\r>",
                "224531" to "7EC 04 62 45 31 02\r>",
                "22119F" to "62 11 9F CC\r>",
                "22119F01" to "62 11 9F 01 CC\r>",
                "222883" to "62 28 83 03 E8\r>",
                "222884" to "62 28 84 FD A8\r>",
                "222885" to "62 28 85 88 B8\r>",
                "222886" to "62 28 86 75 30\r>",
                "222487" to "62 24 87 04 D2\r>",
                "22194001" to "62 19 40 01 5A\r>",
                "221940" to "62 19 40 5A\r>",
                "2241B2" to "62 41 B2 03 E8\r>",
                "2241B4" to "62 41 B4 07\r>",
                "2241B6" to "62 41 B6 07 D0\r>",
                "22801E" to "62 80 1E 28\r>",
                "22801F" to "62 80 1F 96\r>",
            ),
        )
    }

    private fun eventPayloads(event: String): List<JSONObject> {
        // The session log flushes after every line, so the .jsonl is readable without closing it.
        val logFile =
            logDir.listFiles()?.firstOrNull { it.name.endsWith(".jsonl") }
                ?: return emptyList()
        return logFile
            .readLines()
            .mapNotNull { line ->
                if (line.isBlank()) null else JSONObject(line)
            }.filter { it.optString("type") == "event" }
            .map { it.optJSONObject("payload") ?: JSONObject() }
            .filter { it.optString("event") == event }
    }

    /**
     * Minimal [ObdPollingEngine] that returns scripted raw responses for the per-PID broadcast
     * polls [PidPollingState.runScheduledPolls] issues, with an ELM-prompt default for everything
     * else. No Bluetooth socket is involved.
     */
    private class ScriptedEngine(
        service: ObdService,
    ) : ObdPollingEngine(service) {
        val responses = HashMap<String, String>()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String = responses[command] ?: ">"

        override fun sendCommand(
            command: String?,
            timeoutMs: Long,
        ): String = responses[command] ?: ">"
    }

    /** Small [LiveSampleReader.SampleContext] that adds the metadata callbacks production adds. */
    private class CapturingContext : LiveSampleReader.SampleContext {
        private var samples = 0

        override fun incrementSampleCount(): Int = ++samples

        override fun supportedPidsSummary(): String = "0100,0120,0140"

        override fun appendSessionHealth(sample: JSONObject) {
            sample.put(
                "health",
                org.json
                    .JSONArray()
                    .put("gap")
                    .put("background")
                    .put("disabledPids"),
            )
        }

        override fun appendLocation(sample: JSONObject) {
            sample.put("lat", 34.05)
            sample.put("lng", -118.25)
        }

        override fun redactedVin(): String? = "VIN-REDACTED"
    }

    private class ThrowingHealthContext : LiveSampleReader.SampleContext {
        override fun incrementSampleCount(): Int = 1

        override fun supportedPidsSummary(): String = ""

        override fun appendSessionHealth(sample: JSONObject): Unit = throw org.json.JSONException("health failed")

        override fun appendLocation(sample: JSONObject) = Unit

        override fun redactedVin(): String? = null
    }
}
