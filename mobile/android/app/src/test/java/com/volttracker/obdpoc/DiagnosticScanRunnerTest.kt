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
        assertTrue(
            "full scan progress should mention freeze frames and live data",
            service.statusDetails.contains("Reading DTCs, freeze frames, and live-data probes..."),
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
        assertEquals("full", telemetry.getString("scanProfile"))
    }

    @Test
    fun quickProfileReadsCodesButSkipsDeepModuleProbes() {
        val service = FakeService()
        val engine = FakeEngine(service)

        DiagnosticScanRunner(service, engine).run(DiagnosticScanProfile.QUICK)

        // Quick scan still does protocol detection, VIN, and the generic DTC reads...
        assertTrue("quick scan still reads VIN", engine.commands.contains("0902"))
        assertTrue("quick scan still reads stored codes", engine.commands.contains("03"))
        assertTrue(engine.commands.contains("07"))
        assertTrue(engine.commands.contains("0A"))
        assertEquals("ATSH7DF", engine.commands[engine.commands.size - 1])

        // ...but skips the slow Volt module + freeze-frame + live-data sweep.
        assertTrue("quick scan must skip HV/charger module", !engine.commands.contains("ATSH7E4"))
        assertTrue("quick scan must skip pack-voltage module", !engine.commands.contains("ATSH7E1"))
        assertTrue("quick scan must skip brake module", !engine.commands.contains("ATSH7E6"))
        assertTrue("quick scan must skip BECM layout module", !engine.commands.contains("ATSH7E7"))
        assertTrue("quick scan must skip freeze frames", !engine.commands.contains("0200"))

        val telemetry = service.lastTelemetry()!!
        assertEquals("quick", telemetry.getString("scanProfile"))
        assertEquals("scan-complete", service.lastStatusState())
        assertTrue(
            "quick scan must not advertise Volt module progress",
            !service.statusDetails.contains("Reading Volt battery and charger modules..."),
        )
        assertTrue(
            "quick scan progress must not claim to read freeze frames or live data",
            !service.statusDetails.contains("Reading DTCs, freeze frames, and live-data probes..."),
        )
        assertTrue(
            "quick scan should surface stored-code progress",
            service.statusDetails.contains("Reading stored diagnostic trouble codes..."),
        )
    }

    @Test
    fun onlyModeThreeStoredCodesFeedTheNotificationBaseline() {
        val service = FakeService()
        val engine = FakeEngine(service)
        // Distinct codes per mode: stored (03) P0133, pending (07) U0073, permanent (0A) P25A2.
        engine.responses["03"] = "43 01 01 33 00 00\r>"
        engine.responses["07"] = "47 01 C0 73 00 00\r>"
        engine.responses["0A"] = "4A 01 25 A2 00 00\r>"

        DiagnosticScanRunner(service, engine).run(DiagnosticScanProfile.QUICK)

        val dtcCodes = service.lastTelemetry()!!.getJSONArray("dtcCodes")
        val codes = (0 until dtcCodes.length()).map { dtcCodes.getString(it) }.toSet()
        // Only the Mode 03 stored code seeds the notification-facing set — matching AutoDtcScanRunner's
        // Mode-03-only baseline. The pending (07) and permanent (0A) codes are still probed (persisted
        // per-command elsewhere) but must NOT enter this diff set, or a permanent-only code would
        // oscillate against the auto-scan baseline and re-fire a false NewDtc alert forever.
        assertEquals(setOf("P0133"), codes)
    }

    private class FakeService : ObdService() {
        val statuses: MutableList<String?> = ArrayList()
        val statusDetails: MutableList<String?> = ArrayList()
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
        val responses = HashMap<String, String>()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commands.add(command)
            return responses[command] ?: "OK>"
        }

        override fun appendLocation(sample: JSONObject) {
            sample.put("latitude", 42.0)
            sample.put("longitude", -71.0)
        }
    }
}
