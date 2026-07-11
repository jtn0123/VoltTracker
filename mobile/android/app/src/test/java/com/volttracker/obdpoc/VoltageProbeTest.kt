package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Pure JVM tests for [VoltageProbe.parseVolts]. The decode is the only piece worth unit-testing in
 * isolation; the [VoltageProbe.run] path is exercised end-to-end on device.
 */
class VoltageProbeTest {
    @Test
    fun parsesStandardResponse() {
        // 4142 31 D4 → (0x31 * 256 + 0xD4) / 1000 = (12544 + 212) / 1000 = 12.756 V
        val v = VoltageProbe.parseVolts("41 42 31 D4\r>")
        assertNotNull(v)
        assertEquals(12.756, v!!, EPS)
    }

    @Test
    fun parsesResponseWithSearchingPrefix() {
        val v = VoltageProbe.parseVolts("SEARCHING...\r41 42 31 D4\r>")
        assertNotNull(v)
        assertEquals(12.756, v!!, EPS)
    }

    @Test
    fun parsesLowercaseAndExtraWhitespace() {
        val v = VoltageProbe.parseVolts("  41 42 2f 7c  \r>")
        assertNotNull(v)
        // (0x2F * 256 + 0x7C) / 1000 = (12032 + 124) / 1000 = 12.156
        assertEquals(12.156, v!!, EPS)
    }

    @Test
    fun rejectsNoDataResponse() {
        assertNull(VoltageProbe.parseVolts("NO DATA\r>"))
    }

    @Test
    fun rejectsNullResponse() {
        assertNull(VoltageProbe.parseVolts(null))
    }

    @Test
    fun rejectsMissingHeader() {
        // 4101 is a different PID's reply; no 4142 marker means we can't decode.
        assertNull(VoltageProbe.parseVolts("41 01 BE 1F A8 13\r>"))
    }

    @Test
    fun rejectsTruncatedPayload() {
        // 4142 with only one byte of data — not enough.
        assertNull(VoltageProbe.parseVolts("41 42 30\r>"))
    }

    @Test
    fun acceptsLowVoltageReadingForC9Hint() {
        // 4142 00 01 → 0.001 V — a totally dead battery. We deliberately accept this so the
        // dashboard's low-voltage hint (C9) fires exactly when the user most needs it; the prior
        // 4 V floor was hiding the worst-case readings.
        val v = VoltageProbe.parseVolts("41 42 00 01\r>")
        assertNotNull(v)
        assertEquals(0.001, v!!, EPS)
    }

    @Test
    fun rejectsImplausiblyHighVoltage() {
        // 4142 FF FF → 65.535 V, above the 32 V ceiling.
        assertNull(VoltageProbe.parseVolts("41 42 FF FF\r>"))
    }

    @Test
    fun acceptsTypicalDeadBatteryReading() {
        // 4142 1F 40 → (0x1F * 256 + 0x40) / 1000 = (7936 + 64) / 1000 = 8.0 V — old/cold battery.
        val v = VoltageProbe.parseVolts("41 42 1F 40\r>")
        assertNotNull(v)
        assertEquals(8.0, v!!, EPS)
    }

    @Test
    fun runSendsPid42WithDefaultTimeoutAndStoresDecodedVoltage() {
        val service = ObdService()
        val sender = RecordingSender("41 42 31 D4\r>")

        VoltageProbe(service).run(sender)

        assertEquals(VoltageProbe.PID, sender.command)
        assertEquals(VoltageProbe.DEFAULT_TIMEOUT_MS, sender.timeoutMs)
        assertEquals(12.756, lastVoltage(service)!!, EPS)
    }

    @Test
    fun runReturnsWithoutTouchingSenderWhenSenderMissing() {
        VoltageProbe(ObdService()).run(null)
    }

    @Test
    fun runSwallowsIoRuntimeAndDecodeFailures() {
        val ioService = ObdService()
        VoltageProbe(ioService).run(RecordingSender(ioFailure = IOException("boom")))
        assertNull(lastVoltage(ioService))

        val runtimeService = ObdService()
        VoltageProbe(runtimeService).run(RecordingSender(runtimeFailure = IllegalStateException("boom")))
        assertNull(lastVoltage(runtimeService))

        val decodeService = ObdService()
        VoltageProbe(decodeService).run(RecordingSender("NO DATA\r>"))
        assertNull(lastVoltage(decodeService))
    }

    companion object {
        private const val EPS = 1e-3

        // B5 consolidated the service's lastVoltage field into the SessionOutcome snapshot;
        // read it through the test accessor instead of reflecting on the removed field.
        private fun lastVoltage(service: ObdService): Double? = service.sessionOutcomeForTest().voltage
    }

    private class RecordingSender(
        private val response: String? = null,
        private val ioFailure: IOException? = null,
        private val runtimeFailure: RuntimeException? = null,
    ) : VoltageProbe.Sender {
        var command: String? = null
        var timeoutMs: Long = -1L

        override fun transactOneShot(
            command: String,
            timeoutMs: Long,
        ): String? {
            this.command = command
            this.timeoutMs = timeoutMs
            ioFailure?.let { throw it }
            runtimeFailure?.let { throw it }
            return response
        }
    }
}
