package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the probe sweep and scan-telemetry publishing of [DiagnosticScanRunner]. */
class DiagnosticScanRunnerTest {
    @Test
    fun runSweepsProbesAndPublishesScanTelemetry() {
        val service = FakeService()
        val engine = FakeEngine(service)

        DiagnosticScanRunner(service, engine).run()

        assertEquals("scanning", service.statuses[0])
        assertEquals("scan-complete", service.lastStatusState())
        assertEquals("Scan complete for Test adapter", service.lastNotification())

        assertEquals("ATI", engine.commands[0])
        assertTrue(engine.commands.contains("ATSP0"))
        assertTrue(engine.commands.contains("03"))
        assertTrue(engine.commands.contains("0902"))
        assertTrue(engine.commands.contains("ATSH7E4"))
        assertTrue(engine.commands.contains("ATSH7E6"))
        assertTrue(engine.commands.contains("ATSH7E7"))
        assertTrue("known-rejected TPMS receiver header must not be selected", !engine.commands.contains("ATSH760"))
        assertEquals("ATSH7DF", engine.commands[engine.commands.size - 1])

        val telemetry = service.lastTelemetry()!!
        assertEquals("scan", telemetry.getString("source"))
        assertEquals("Test adapter", telemetry.getString("adapter"))
        assertTrue(telemetry.getBoolean("connected"))
        assertEquals(42.0, telemetry.getDouble("latitude"), 0.001)
        assertEquals(-71.0, telemetry.getDouble("longitude"), 0.001)

        val raw = telemetry.getString("raw")
        assertTrue(raw.contains("adapter: Test adapter"))
        assertTrue(raw.contains("ATI: OK"))
        assertTrue(raw.contains("volt-discovery: restore auto protocol for live + Volt probes"))
        assertTrue(raw.contains("volt-discovery: ATSH7E6 brake-module probes"))
        assertTrue(raw.contains("volt-discovery: ATSH7E7 BECM cell-interface layout probes"))
        assertTrue("known-rejected TPMS probes must stay out of broad scans", !raw.contains("tpms-discovery:"))
    }

    private class FakeService : ObdService() {
        val statuses: MutableList<String?> = ArrayList()
        val notifications: MutableList<String?> = ArrayList()
        var telemetry: JSONObject? = null

        init {
            activeName = "Test adapter"
        }

        override fun broadcastTelemetry(payload: JSONObject?) {
            telemetry = payload
        }

        override fun broadcastStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            statuses.add(state)
        }

        override fun updateNotification(text: String?) {
            notifications.add(text)
        }

        fun lastStatusState(): String? = statuses[statuses.size - 1]

        fun lastNotification(): String? = notifications[notifications.size - 1]

        fun lastTelemetry(): JSONObject? = telemetry
    }

    private class FakeEngine(
        service: ObdService,
    ) : ObdPollingEngine(service) {
        val commands: MutableList<String?> = ArrayList()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commands.add(command)
            return "OK>"
        }

        override fun appendLocation(sample: JSONObject) {
            sample.put("latitude", 42.0)
            sample.put("longitude", -71.0)
        }
    }
}
