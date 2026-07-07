package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the Mode 04 clear-DTC sequence and response parsing of [ClearDtcRunner]. */
class ClearDtcRunnerTest {
    @Test
    fun runBroadcastsSuccessForExplicitPositiveResponse() {
        val service = FakeService()
        val engine = FakeEngine(service, "04\r44\r>")

        ClearDtcRunner(service, engine).run()

        assertEquals("04", engine.command)
        assertEquals(6000L, engine.timeoutMs)
        assertEquals("codes-cleared", service.lastStatusState())
        assertEquals("DTCs cleared on Test adapter", service.lastNotification())
        assertTrue(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
        assertEquals("ok", service.lastTelemetry()!!.getString("clearDtcCode"))
        assertEquals("04 44", service.lastTelemetry()!!.getString("raw"))
    }

    @Test
    fun runPrefersNegativeResponseWhenPayloadAlsoContains44() {
        val service = FakeService()
        val engine = FakeEngine(service, "44\r7F 04 22\r>")

        ClearDtcRunner(service, engine).run()

        assertEquals("error", service.lastStatusState())
        assertTrue(service.lastStatusDetail()!!.contains("conditions not correct"))
        assertEquals("Clear-codes failed on Test adapter", service.lastNotification())
        assertFalse(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
        assertEquals("22", service.lastTelemetry()!!.getString("clearDtcCode"))
    }

    @Test
    fun runTreatsResponsePendingBeforePositiveReplyAsCleared() {
        val service = FakeService()
        // 7F 04 78 = requestCorrectlyReceived-ResponsePending; the ECU then confirms the clear with
        // 44. The 0x78 must be treated as non-terminal so the trailing 44 still reports success.
        val engine = FakeEngine(service, "7F 04 78\r44\r>")

        ClearDtcRunner(service, engine).run()

        assertEquals("codes-cleared", service.lastStatusState())
        assertTrue(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
        assertEquals("ok", service.lastTelemetry()!!.getString("clearDtcCode"))
    }

    @Test
    fun runReportsFailureForResponsePendingWithNoFinalPositive() {
        val service = FakeService()
        // Response-pending that never resolves to a 44: still a failure, just not a terminal NRC.
        val engine = FakeEngine(service, "7F 04 78\r>")

        ClearDtcRunner(service, engine).run()

        assertEquals("error", service.lastStatusState())
        assertFalse(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
    }

    @Test
    fun runReportsFailureForUnusableReply() {
        val service = FakeService()
        val engine = FakeEngine(service, "NO DATA>")

        ClearDtcRunner(service, engine).run()

        assertEquals("error", service.lastStatusState())
        assertTrue(service.lastStatusDetail()!!.contains("No usable reply"))
        assertFalse(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
        assertEquals("", service.lastTelemetry()!!.getString("clearDtcCode"))
    }

    @Test
    fun runReportsSpecificMessagesForKnownNegativeResponseCodes() {
        val expectations =
            mapOf(
                "11" to "does not support",
                "12" to "sub-function",
                "13" to "invalid message length",
                "33" to "security access denied",
                "31" to "NRC 0x31",
            )

        for ((nrc, phrase) in expectations) {
            val service = FakeService()
            val engine = FakeEngine(service, "7F 04 $nrc\r>")

            ClearDtcRunner(service, engine).run()

            assertEquals("error", service.lastStatusState())
            assertTrue("NRC $nrc should mention $phrase", service.lastStatusDetail()!!.contains(phrase))
            assertFalse(service.lastTelemetry()!!.getBoolean("clearDtcOk"))
            assertEquals(nrc, service.lastTelemetry()!!.getString("clearDtcCode"))
        }
    }

    @Test
    fun runTruncatesOversizedRawTelemetry() {
        val service = FakeService()
        val oversized = "7F 04 31 " + "A".repeat(400)
        val engine = FakeEngine(service, oversized)

        ClearDtcRunner(service, engine).run()

        assertEquals(256, service.lastTelemetry()!!.getString("raw").length)
    }

    @Test
    fun positiveMode04ResponseMustBeExplicitToken() {
        assertTrue(ClearDtcRunner.hasPositiveMode04Response("04 44"))
        assertTrue(ClearDtcRunner.hasPositiveMode04Response("SEARCHING... 44"))

        assertFalse(ClearDtcRunner.hasPositiveMode04Response("7F 04 22"))
        assertFalse(ClearDtcRunner.hasPositiveMode04Response("014400"))
        assertFalse(ClearDtcRunner.hasPositiveMode04Response("NO DATA"))
        assertFalse(ClearDtcRunner.hasPositiveMode04Response(null))
    }

    @Test
    fun negativeResponseCodeHandlesSpacedAndCompactPayloads() {
        assertEquals("22", ClearDtcRunner.extractNegativeResponseCode("7F 04 22"))
        assertEquals("11", ClearDtcRunner.extractNegativeResponseCode("SEARCHING... 7F0411"))
        assertEquals("22", ClearDtcRunner.extractNegativeResponseCode("7F 04 78 7F 04 22"))
        assertEquals("78", ClearDtcRunner.extractNegativeResponseCode("7F 04 78"))
        assertEquals("", ClearDtcRunner.extractNegativeResponseCode("44"))
    }

    @Test
    fun compactResponsePreservesFrameBoundaries() {
        assertEquals("04 44", ClearDtcRunner.compactResponse("04\r44\r>"))
        assertEquals("7F 04 22", ClearDtcRunner.compactResponse("7F 04 22>"))
        assertEquals("", ClearDtcRunner.compactResponse(null))
    }

    private class FakeService : ObdService() {
        val statuses: MutableList<String?> = ArrayList()
        val details: MutableList<String?> = ArrayList()
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
            details.add(detail)
        }

        override fun updateNotification(text: String?) {
            notifications.add(text)
        }

        fun lastStatusState(): String? = statuses[statuses.size - 1]

        fun lastStatusDetail(): String? = details[details.size - 1]

        fun lastNotification(): String? = notifications[notifications.size - 1]

        fun lastTelemetry(): JSONObject? = telemetry
    }

    private class FakeEngine(
        service: ObdService,
        private val response: String,
    ) : ObdPollingEngine(service) {
        var command: String? = null
        var timeoutMs: Long = 0

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            this.command = command
            this.timeoutMs = timeoutMs
            return response
        }
    }
}
