package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.CellVoltageProbeRunner
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Exercises the 96-cell voltage snapshot pass of [CellVoltageProbeRunner]. */
class CellVoltageProbeRunnerTest {
    @Test
    fun readsAllCellsOnTheContiguousLayout() {
        val service = FakeService()
        // Every cell DID answers; word 0xC7AD decodes to ~3.9 V at the legacy 5/65535 scale.
        val engine = FakeEngine(service) { _ -> "62%s C7AD" }

        CellVoltageProbeRunner(service, engine).run()

        assertEquals("ATSH7E7", engine.commands[0])
        assertTrue("shared prefix cell 1", engine.commands.contains("224181"))
        assertTrue("contiguous tail cell 32", engine.commands.contains("2241A0"))
        assertTrue("contiguous tail cell 96", engine.commands.contains("2241E0"))
        assertFalse("gen1 tail must be skipped when the contiguous layout answers", engine.commands.contains("224200"))

        assertEquals("scan-complete", service.lastStatusState())
        assertTrue(
            "summary should report a full read: ${service.statusDetails.last()}",
            service.statusDetails.last()!!.startsWith("Cell probe complete: 96 of 96 cells read."),
        )
        val cells = service.lastTelemetry()!!.getJSONArray("cellVoltages")
        assertEquals(96, cells.length())
        assertEquals(3.9, cells.getDouble(0), 0.05)
        assertEquals("cell-probe", service.lastTelemetry()!!.getString("source"))
    }

    @Test
    fun fallsBackToTheGen1TailLayoutWhenTheContiguousProbeIsSilent() {
        val service = FakeService()
        val engine =
            FakeEngine(service) { command ->
                val did = command.substring(2).toInt(16)
                // Cells 32-96 only answer at the Gen 1 DIDs (0x4200-0x4240).
                if (did in 0x41A0..0x41E0) "NO DATA" else "62%s C7AD"
            }

        CellVoltageProbeRunner(service, engine).run()

        assertTrue("gen1 tail cell 32", engine.commands.contains("224200"))
        assertTrue("gen1 tail cell 96", engine.commands.contains("224240"))
        assertFalse("contiguous tail beyond the layout pick must be skipped", engine.commands.contains("2241A1"))
        assertTrue(
            "summary should report a full read via the gen1 layout",
            service.statusDetails.last()!!.startsWith("Cell probe complete: 96 of 96 cells read."),
        )
        assertEquals(96, service.lastTelemetry()!!.getJSONArray("cellVoltages").length())
    }

    @Test
    fun decodesTheFieldCalibratedBecmScaleToo() {
        val service = FakeService()
        // Word 0x1700 (5888) is garbage at 5/65535 (~0.45 V) but ~3.68 V at the
        // field-calibrated 1/1600 scale this BECM uses for its min/max cell DIDs.
        val engine = FakeEngine(service) { _ -> "62%s 1700" }

        CellVoltageProbeRunner(service, engine).run()

        val cells = service.lastTelemetry()!!.getJSONArray("cellVoltages")
        assertEquals(96, cells.length())
        assertEquals(3.68, cells.getDouble(0), 0.01)
    }

    @Test
    fun reportsAnHonestFailureWhenTheModuleStaysSilent() {
        val service = FakeService()
        val engine = FakeEngine(service) { _ -> "NO DATA" }

        CellVoltageProbeRunner(service, engine).run()

        assertEquals("scan-complete", service.lastStatusState())
        assertTrue(
            service.statusDetails.last()!!.contains("did not answer"),
        )
        assertFalse("no cellVoltages key on a silent probe", service.lastTelemetry()!!.has("cellVoltages"))
    }

    private class FakeService : ObdService() {
        val statuses: MutableList<String?> = ArrayList()
        val statusDetails: MutableList<String?> = ArrayList()
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
            // No notification channel in unit tests.
        }

        fun lastStatusState(): String? = statuses[statuses.size - 1]

        fun lastTelemetry(): JSONObject? = telemetry
    }

    /**
     * [respond] maps a mode-22 command (e.g. "2241A0") to its raw response template; a "%s" in the
     * template is filled with the command's DID so the decoder sees a matching echo frame.
     */
    private class FakeEngine(
        service: ObdService,
        private val respond: (String) -> String,
    ) : ObdPollingEngine(service) {
        val commands: MutableList<String> = ArrayList()

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            val clean = command ?: ""
            commands.add(clean)
            if (!clean.startsWith("22")) {
                return "OK>"
            }
            return String.format(Locale.US, respond(clean), clean.substring(2))
        }
    }
}
