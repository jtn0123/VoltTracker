package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.SessionRecorder
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
    fun shutdownSpeedSentinelDoesNotInventAPlugConnection() {
        engine.responses["010D"] = "410DFF"
        engine.responses["010C"] = "410C0000"
        engine.responses["ATRV"] = "12.3V"
        engine.responses["2243AE"] = "6243AE00"
        val sample = readUntil("chargeState")
        assertEquals("None", sample.getString("chargeState"))
        assertEquals("parked", sample.getString("vehicleState"))
    }

    @Test
    fun staleChargerStateAndSpeedSentinelsCannotHoldTheCarAsCharging() {
        var now = System.currentTimeMillis()
        pidPolling.setClockForTesting { now }
        engine.responses["010D"] = "410DFF"
        engine.responses["010C"] = "410C0000"
        engine.responses["ATRV"] = "12.3V"
        engine.responses["2243AE"] = "6243AE02"
        assertEquals("charging", readUntil("chargeState").getString("vehicleState"))
        now += 60_001L
        engine.responses["2243AE"] = "NO DATA"
        pidPolling.pollSoon("ATRV")
        assertEquals("parked", reader.read(context).getString("vehicleState"))
    }

    @Test
    fun directTiresSurviveTheFullSampleThenBroadcastMerge() {
        engine.responses["225008"] = "6250080F80"
        engine.responses["225007"] = "6250070F80"
        engine.responses["225006"] = "6250060FC0"
        engine.responses["225005"] = "6250050F80"
        val sample = readUntil("tirePressureFlKpa")
        val broadcast = SwcanReadings()
        broadcast.record(
            SwcanFrameDecoder.decodeAll(SwcanFrameDecoder.parseMonitorOutput("10 3D 40 40 24 24 3D 3E 37 38")),
            System.currentTimeMillis() - 1_200_000L,
        )
        broadcast.appendTo(sample, System.currentTimeMillis())
        assertEquals(248, sample.getInt("tirePressureFlKpa"))
        assertEquals(252, sample.getInt("tirePressureFrKpa"))
        assertTrue(sample.getLong("tirePressureStaleMs") < 10_000L)
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
        assertEquals(1000, sample.optInt("acPowerW"))
        assertEquals(2000, sample.optInt("cabinHeatPowerW"))
        assertEquals(1200, sample.optInt("batteryCoolantPumpRpm"))
        assertEquals(2000, sample.optInt("batteryHeaterPowerW"))
        assertEquals(35.0, sample.optDouble("outsideTempC"), 0.01)
        assertEquals("driving_gas", sample.optString("vehicleState"))
        assertFalse(sample.optJSONArray("vehicleStateReasons")!!.isNull(0))
        assertEquals(3, sample.optJSONArray("health")!!.length())
        assertEquals(34.05, sample.optDouble("lat"), 0.001)
        assertTrue("sample should carry the current-cycle raw transcript", sample.has("raw"))
    }

    @Test
    fun gmOdometerIsPreferredOverTheStandardPid() {
        scriptRichResponses()
        // 0x003C4B00 / 64 = 61,740 km; 01A6 (123,456.7 km) is only the fallback.
        engine.responses["2234B2"] = "62 34 B2 00 3C 4B 00\r>"

        var sample = JSONObject()
        repeat(241) {
            sample = reader.read(context)
        }

        assertEquals(61740.0, sample.optDouble("odometerKm"), 0.01)
        assertEquals(38363.4, sample.optDouble("odometerMiles"), 0.1)
    }

    @Test
    fun prndlCodeIsDecodedToAGearLetterAndTheRawCodeIsKept() {
        engine.responses["222889"] = "62 28 89 08\r>"
        val park = readUntil("prndlRaw")
        assertEquals("P", park.optString("prndlState"))
        assertEquals(8, park.optInt("prndlRaw"))
        assertEquals("confirmed", park.optString("gearConfidence"))
    }

    @Test
    fun anUnseenPrndlCodeShowsAsUnknownNotAGuess() {
        engine.responses["222889"] = "62 28 89 0D\r>"
        val sample = readUntil("prndlRaw")
        assertEquals("?", sample.optString("prndlState"))
        assertEquals(13, sample.optInt("prndlRaw"))
        assertEquals("unknown", sample.optString("gearConfidence"))
    }

    @Test
    fun bodyComputerTiresFillTheTireKeysAndLogTheFirstRead() {
        // One word per wheel in 1/16 kPa; the RR sensor reports "no reading" (FFFF).
        engine.responses["225008"] = "62 50 08 0F 00\r>"
        engine.responses["225007"] = "62 50 07 0F 40\r>"
        engine.responses["225006"] = "62 50 06 0F 80\r>"
        engine.responses["225005"] = "62 50 05 FF FF\r>"
        val sample = readUntil("tirePressureFlKpa")
        assertEquals(240, sample.getInt("tirePressureFlKpa"))
        assertEquals(244, sample.getInt("tirePressureRlKpa"))
        assertEquals(248, sample.getInt("tirePressureFrKpa"))
        assertFalse(sample.has("tirePressureRrKpa"))
        assertTrue(sample.has("tirePressureStaleMs"))
        reader.read(context)
        assertEquals(1, countAllEvents("bcm_tires_first_read"))
        val responses = eventPayloads("bcm_tires_first_read").single().optString("responses")
        assertTrue(responses, responses.startsWith("62 50 08 0F 00"))
        for (wheel in BcmTirePressure.Wheel.entries) {
            assertEquals(0, countEvents("pid_parse_failed", wheel.command))
        }
    }

    @Test
    fun gen2StatusReadsFillTheirKeysAndLogEachNewStartReason() {
        engine.responses["222439"] = "62 24 39 00 00 00 08\r>"
        engine.responses["2243AE"] = "62 43 AE 02\r>"
        engine.responses["228537"] = "62 85 37 00 3B\r>"
        // A charge record is multi-frame: ATH0 + CAF1 prints a length line and numbered segments.
        engine.responses["2243CB"] = "009\r0: 62 43 CB 01 00 00\r1: 00 00 00 AA AA AA AA\r\r>"
        val sample = readUntil("chargeRecord1")
        assertEquals("Full charge", sample.getString("chargeRecord1"))
        assertTrue(sample.has("chargeRecordsStaleMs"))
        assertEquals("Battery charge low", sample.getString("engineStartReason"))
        assertTrue(sample.has("engineStartReasonStaleMs"))
        assertEquals("Charging", sample.getString("chargeState"))
        assertEquals(590, sample.getInt("remoteStartSecondsLeft"))
        assertFalse("not answered, not sent", sample.has("chargeRecord2"))
        assertEquals(0, countEvents("pid_parse_failed", "2243CB"))
        assertEquals("logged once, not every sample", 1, countAllEvents("engine_start_reason"))

        // The engine stops (the field clears), then starts again for the same reason: a new line.
        engine.responses["222439"] = "62 24 39 00 00 00 00\r>"
        readUntilValue("engineStartReason", "None")
        engine.responses["222439"] = "62 24 39 00 00 00 08\r>"
        readUntilValue("engineStartReason", "Battery charge low")
        val reasons = eventPayloads("engine_start_reason").map { it.optString("reason") }
        assertEquals(listOf("Battery charge low", "Battery charge low"), reasons)
    }

    @Test
    fun gen2ExtraReadsFillTheirKeys() {
        engine.responses["22900D"] = "62 90 0D 02\r>"
        engine.responses["2241A4"] = "62 41 A4 3E\r>"
        engine.responses["22452D"] = "62 45 2D B8\r>"
        engine.responses["22452E"] = "62 45 2E AD\r>"
        engine.responses["22002C"] = "62 00 2C 1F\r>"
        engine.responses["223040"] = "62 30 40 05 14\r>"
        engine.responses["223041"] = "62 30 41 04 D8\r>"
        // The 12 V capacity is a two-frame BCM reply.
        engine.responses["22CB31"] = "008\r0: 62 CB 31 29 80 00\r1: 00 00 AA AA AA AA AA\r\r>"
        val sample = readUntil("aux12vCapacityAh")
        assertEquals(41.5, sample.getDouble("aux12vCapacityAh"), 0.0)
        assertTrue(sample.has("aux12vCapacityStaleMs"))
        assertEquals("Remote start", sample.getString("remoteStartReason"))
        assertTrue(sample.has("remoteStartCountStaleMs"))
        assertEquals(22, sample.getInt("batteryCoolantC"))
        assertTrue(sample.has("batteryCoolantStaleMs"))
        assertEquals(72, sample.getInt("chargerAcDutyPct"))
        assertEquals(68, sample.getInt("chargerOutputDutyPct"))
        assertTrue(sample.has("chargerDutyStaleMs"))
        assertEquals(12, sample.getInt("egrCommandedPct"))
        assertEquals(90, sample.getInt("egrCoolerTemp1C"))
        assertEquals(84, sample.getInt("egrCoolerTemp2C"))
        assertTrue(sample.has("egrStaleMs"))
        for (command in arrayOf("22900D", "2241A4", "22452D", "22452E", "22002C", "223040", "223041", "22CB31")) {
            assertEquals(command, 0, countEvents("pid_parse_failed", command))
        }
    }

    private fun readUntilValue(
        key: String,
        value: String,
    ) {
        repeat(400) {
            if (reader.read(context).optString(key) == value) return
        }
        throw AssertionError("$key never read $value")
    }

    /** Reads until the slow-cadence PID feeding [key] has been polled (bounded). */
    private fun readUntil(key: String): JSONObject {
        repeat(400) {
            val sample = reader.read(context)
            if (sample.has(key)) return sample
        }
        throw AssertionError("$key never appeared")
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
                "221940" to "62 19 40 5A\r>",
                "2241B2" to "62 41 B2 03 E8\r>",
                "2241B4" to "62 41 B4 07 D0\r>",
                "22435A" to "62 43 5A 30\r>",
                "2241B6" to "62 41 B6 07 D0\r>",
                "22801E" to "62 80 1E 28\r>",
                "22801F" to "62 80 1F 96\r>",
            ),
        )
    }

    private fun eventPayloads(event: String): List<JSONObject> {
        // Event lines flush immediately (and also flush any telemetry batch), so the .jsonl is
        // readable without closing it.
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
