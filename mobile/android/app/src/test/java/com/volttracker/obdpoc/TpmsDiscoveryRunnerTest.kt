package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the read-only detail-probe runner with a fake engine and no real Bluetooth transport. */
class TpmsDiscoveryRunnerTest {
    @Test
    fun lowRiskStageRunsProfileProbeAndPublishesTelemetry() {
        val service = FakeService()
        val engine = FakeEngine(service)

        TpmsDiscoveryRunner(service, engine).run("AA:BB:CC:DD:EE:FF", EnhancedPidProfiles.STAGE_LOW_RISK)

        assertEquals("scanning", service.statuses.first())
        assertTrue(service.statusDetails.first()!!.contains("low-risk Detail Probe"))
        assertEquals("scan-complete", service.statuses.last())
        assertTrue(service.statusDetails.last()!!.contains("low-risk"))
        assertEquals("Detail Probe (low-risk) complete for Test adapter", service.notifications.last())

        assertEquals("ATI", engine.commands[0])
        assertTrue(engine.commands.contains("ATDP"))
        assertTrue(engine.commands.contains("ATDPN"))
        assertTrue(engine.commands.contains("ATRV"))
        assertEquals("ATSH7DF", engine.commands.last())

        val telemetry = service.lastTelemetry()!!
        assertEquals("detail-probe", telemetry.getString("source"))
        assertEquals("low-risk", telemetry.getString("scanStage"))
        assertTrue(telemetry.getBoolean("connected"))
        assertEquals("Test adapter", telemetry.getString("adapter"))
        val raw = telemetry.getString("raw")
        assertTrue(raw.contains("adapter: Test adapter"))
        assertTrue(raw.contains("stage: low-risk"))
        assertTrue(raw.contains("low-risk-discovery"))
    }

    @Test
    fun unknownStageFallsBackToTiresAndIncludesPassiveTargets() {
        val service = FakeService()
        val engine = FakeEngine(service)

        TpmsDiscoveryRunner(service, engine).run("AA:BB:CC:DD:EE:FF", " not-a-stage ")

        val telemetry = service.lastTelemetry()!!
        assertEquals(EnhancedPidProfiles.STAGE_TIRES, telemetry.getString("scanStage"))
        assertTrue(service.statusDetails.first()!!.contains("tires Detail Probe"))
        val raw = telemetry.getString("raw")
        assertTrue(raw.contains("stage: tires"))
        if (EnhancedPidProfiles.passiveProfiles().isNotEmpty()) {
            assertTrue(raw.contains("passive-target"))
        }
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

        fun lastTelemetry(): JSONObject? = telemetry
    }

    private class FakeEngine(
        service: ObdService,
    ) : ObdPollingEngine(service) {
        val commands: MutableList<String> = ArrayList()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            val clean = command ?: ""
            commands.add(clean)
            return "OK>"
        }
    }
}
