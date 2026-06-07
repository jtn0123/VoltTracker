package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** Counts `pid_parse_failed` event lines whose `command` payload matches [command]. */
    private fun countEvents(
        event: String,
        command: String,
    ): Int = eventPayloads(event).count { it.optString("command") == command }

    private fun countAllEvents(event: String): Int = eventPayloads(event).size

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

    /** No-op [LiveSampleReader.SampleContext] — the parse-failure path does not depend on it. */
    private class CapturingContext : LiveSampleReader.SampleContext {
        private var samples = 0

        override fun incrementSampleCount(): Int = ++samples

        override fun supportedPidsSummary(): String = ""

        override fun appendSessionHealth(sample: JSONObject) = Unit

        override fun appendLocation(sample: JSONObject) = Unit

        override fun redactedVin(): String? = null
    }
}
