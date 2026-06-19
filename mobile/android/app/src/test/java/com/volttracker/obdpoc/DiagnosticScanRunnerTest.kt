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
        assertTrue(
            "scan should surface protocol progress",
            service.statusDetails.contains("Checking standard OBD protocols, capability pages, and VIN..."),
        )
        assertTrue(
            "scan should surface Volt module progress",
            service.statusDetails.contains("Reading Volt battery and charger modules..."),
        )
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
        assertEquals("full", telemetry.getString("scanProfile"))
        assertEquals("Test adapter", telemetry.getString("adapter"))
        assertTrue(telemetry.getBoolean("connected"))
        assertEquals(42.0, telemetry.getDouble("latitude"), 0.001)
        assertEquals(-71.0, telemetry.getDouble("longitude"), 0.001)

        val raw = telemetry.getString("raw")
        assertTrue(raw.contains("adapter: Test adapter"))
        assertTrue(raw.contains("ATI: OK"))
        assertTrue(raw.contains("scan-stage: adapter"))
        assertTrue(raw.contains("volt-discovery: restore auto protocol for live + Volt probes"))
        assertTrue(raw.contains("volt-discovery: ATSH7E6 brake-module probes"))
        assertTrue(raw.contains("volt-discovery: ATSH7E7 BECM cell-interface layout probes"))
        assertTrue("known-rejected TPMS probes must stay out of broad scans", !raw.contains("tpms-discovery:"))

        val stages = telemetry.getJSONArray("scanStages")
        assertEquals("adapter", stages.getJSONObject(0).getString("name"))
        assertEquals("standard-capabilities", stages.getJSONObject(1).getString("name"))
        assertEquals("tpms-discovery", stages.getJSONObject(stages.length() - 1).getString("name"))
        assertTrue(stages.getJSONObject(0).getLong("durationMs") >= 0L)
        assertEquals(4, stages.getJSONObject(0).getInt("commandCount"))
        assertEquals(4, stages.getJSONObject(0).getInt("positiveCount"))
    }

    @Test
    fun quickDtcProfileSkipsExpensiveVoltDiscoveryStages() {
        val service = FakeService()
        val engine = FakeEngine(service)

        DiagnosticScanRunner(service, engine).run(DiagnosticScanProfile.QUICK_DTC)

        assertTrue(engine.commands.contains("03"))
        assertTrue(engine.commands.contains("07"))
        assertTrue(engine.commands.contains("0A"))
        assertTrue(engine.commands.contains("ATSH7DF"))
        assertTrue("quick DTC scan should not request VIN", !engine.commands.contains("0902"))
        assertTrue("quick DTC scan should not enter Volt battery headers", !engine.commands.contains("ATSH7E4"))
        assertTrue("quick DTC scan should not enter TPMS receiver headers", !engine.commands.contains("ATSH760"))

        val telemetry = service.lastTelemetry()!!
        assertEquals("quick_dtc", telemetry.getString("scanProfile"))
        val stages = telemetry.getJSONArray("scanStages")
        assertEquals(3, stages.length())
        assertEquals("adapter", stages.getJSONObject(0).getString("name"))
        assertEquals("quick-dtc", stages.getJSONObject(1).getString("name"))
        assertEquals("restore-broadcast-header", stages.getJSONObject(2).getString("name"))
        assertEquals(5, stages.getJSONObject(1).getInt("commandCount"))
    }

    @Test
    fun batteryProfileReadsBatteryChargerStagesButSkipsBroadDiscovery() {
        val service = FakeService()
        val engine = FakeEngine(service)

        DiagnosticScanRunner(service, engine).run(DiagnosticScanProfile.BATTERY)

        assertTrue(engine.commands.contains("0902"))
        assertTrue(engine.commands.contains("03"))
        assertTrue(engine.commands.contains("ATSH7E4"))
        assertTrue(engine.commands.contains("ATSH7E1"))
        assertTrue("battery profile should skip engine/powertrain headers", !engine.commands.contains("ATSH7E0"))
        assertTrue("battery profile should skip brake-module headers", !engine.commands.contains("ATSH7E6"))
        assertTrue("battery profile should skip BECM layout headers", !engine.commands.contains("ATSH7E7"))
        assertTrue("battery profile should skip TPMS receiver headers", !engine.commands.contains("ATSH760"))
        assertEquals("ATSH7DF", engine.commands[engine.commands.size - 1])

        val telemetry = service.lastTelemetry()!!
        assertEquals("battery", telemetry.getString("scanProfile"))
        val stageNames = stageNames(telemetry)
        assertTrue(stageNames.contains("standard-capabilities"))
        assertTrue(stageNames.contains("standard-diagnostics"))
        assertTrue(stageNames.contains("volt-battery-charger"))
        assertTrue(stageNames.contains("restore-broadcast-header"))
        assertTrue("battery profile should not run powertrain stage", !stageNames.contains("volt-powertrain-brake"))
        assertTrue("battery profile should not run TPMS discovery", !stageNames.contains("tpms-discovery"))
    }

    private fun stageNames(telemetry: JSONObject): Set<String> {
        val stages = telemetry.getJSONArray("scanStages")
        val names = LinkedHashSet<String>()
        for (i in 0 until stages.length()) {
            names.add(stages.getJSONObject(i).getString("name"))
        }
        return names
    }

    private class FakeService : ObdService() {
        val statuses: MutableList<String?> = ArrayList()
        val statusDetails: MutableList<String?> = ArrayList()
        val notifications: MutableList<String?> = ArrayList()
        var telemetry: JSONObject? = null

        init {
            activeName = "Test adapter"
            running.set(true)
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
            statusDetails.add(detail)
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
