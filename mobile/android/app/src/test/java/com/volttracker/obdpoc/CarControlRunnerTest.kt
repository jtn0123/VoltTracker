package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.CarControlRunner
import com.volttracker.obdpoc.engine.ElmConnection
import com.volttracker.obdpoc.engine.SwcanListenRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarControlRunnerTest {
    private class FakeIo : CarControlRunner.Io {
        val commands = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val replies = mutableMapOf("ATDPN" to "A6\r\r>")
        val dpnQueue = ArrayDeque<String>()
        var monitorText = "STOPPED\r\r>"
        var monitorPrompt = true
        var transmitReply = "OK\r\r>"
        var failTransmitAt = -1
        var pauseOk = true
        var enabled = true
        var adapter = CarControlGate.Adapter.READY
        var confirmations = mutableSetOf<CarCommand>()
        var liveCycles = 0L
        var reinitCount = 0
        var exclusiveDepth = 0
        var sentOutsideLock = 0
        val readbacks = mutableListOf<SwcanReading>()
        private var transmits = 0

        override fun send(
            command: String,
            timeoutMs: Long,
        ): String {
            if (exclusiveDepth == 0) sentOutsideLock += 1
            commands.add(command)
            if (command.startsWith("STPX")) {
                transmits += 1
                return if (transmits - 1 == failTransmitAt) "CAN ERROR\r\r>" else transmitReply
            }
            if (command == "ATDPN" && dpnQueue.isNotEmpty()) return dpnQueue.removeFirst()
            return replies[command] ?: "OK\r\r>"
        }

        override fun monitor(
            command: String,
            listenMs: Long,
            stopTimeoutMs: Long,
        ): ElmConnection.MonitorResult {
            commands.add(command)
            return ElmConnection.MonitorResult(monitorText, monitorPrompt, false, false)
        }

        override fun pause(ms: Long): Boolean = pauseOk

        override fun reinitialize() {
            reinitCount += 1
            commands.add("<reinit>")
        }

        override fun liveCycleCount(): Long = liveCycles

        override fun <T> exclusive(block: () -> T): T {
            exclusiveDepth += 1
            try {
                return block()
            } finally {
                exclusiveDepth -= 1
            }
        }

        override fun logEvent(
            event: String,
            vararg pairs: String,
        ) {
            events.add(event to pairs.toList().chunked(2).associate { it[0] to it[1] })
        }

        override fun controlsEnabled(): Boolean = enabled

        override fun adapterCapability(): CarControlGate.Adapter = adapter

        override fun consumeConfirmation(command: CarCommand): Boolean = confirmations.remove(command)

        override fun recordReadback(
            readings: List<SwcanReading>,
            atMs: Long,
        ) {
            readbacks.addAll(readings)
        }

        fun event(name: String): Map<String, String>? = events.lastOrNull { it.first == name }?.second

        fun stpx(): List<String> = commands.filter { it.startsWith("STPX") }
    }

    private var now = 10_000_000L
    private val io = FakeIo()
    private val runner = CarControlRunner(io, CarControlRunner.Policy(), CarControlGate()) { now }

    private fun parked() {
        runner.observe(
            JSONObject()
                .put("speedKph", 0.0)
                .put("speedKphStaleMs", 0L)
                .put("prndlState", "P")
                .put("prndlStateStaleMs", 0L)
                .put("vehicleState", "parked"),
            now,
        )
    }

    private fun run(
        command: CarCommand,
        confirmed: Boolean = true,
    ): JSONObject {
        parked()
        if (confirmed) io.confirmations.add(command)
        assertNull(runner.request(command))
        // An HS poll cycle answered since the last command, so the post-command health check passes.
        io.liveCycles += 1
        runner.afterSample()
        val sample = JSONObject()
        runner.appendTo(sample, now)
        return sample
    }

    @Test
    fun lockRunsTheOvmsSequenceAndIsConfirmedByTheLockBroadcast() {
        io.monitorText = "0C 41 40 40 00 01 00 07\rSTOPPED\r\r>"
        val sample = run(CarCommand.LOCK)
        assertEquals("confirmed", sample.getString("carControlLastOutcome"))
        assertEquals("lock", sample.getString("carControlLastCommand"))
        assertTrue(sample.getString("carControlLastDetail").contains("doors locked"))
        val expected =
            listOf("ATDPN") + CarControlRunner.SETUP_COMMANDS +
                listOf(
                    "STP 61",
                    "STFPC",
                    "STFPA 00,00",
                    "STPO",
                    "STCSWM 2",
                    CarControlFrames.WAKEUP.toStpx(),
                    "STCSWM 3",
                    CarControlFrames.WAKEUP_BCM.toStpx(),
                    "STP 62",
                    "STFPC",
                    "STFPA 00,00",
                    CarControlFrames.TELEMATICS_LOCK.toStpx(),
                    CarControlFrames.TELEMATICS_FLASH.toStpx(),
                    "STM",
                    CarControlFrames.TELEMATICS_RELEASE.toStpx(),
                    "STM",
                ) + CarControlRunner.RESTORE_COMMANDS + "ATDPN"
        assertEquals(expected, io.commands)
        assertEquals(0, io.sentOutsideLock)
        val event = io.event("car_control")!!
        assertEquals("confirmed", event["outcome"])
        assertEquals("5", event["framesSent"])
        assertEquals("true", event["restored"])
        assertTrue(event["readback"].orEmpty().contains("lock_state=locked"))
        assertEquals("lock", io.event("car_control_request")!!["command"])
        assertTrue(io.readbacks.any { it.field == SwcanField.LOCK_STATE })
    }

    @Test
    fun onlyAllowlistedTransmitsAndConfigCommandsAreEverSent() {
        io.monitorText = "0C 41 40 40 00 01 00 07\rSTOPPED\r\r>"
        for (command in CarCommand.entries) {
            now += 10_000L
            io.confirmations.add(command)
            parked()
            runner.request(command)
            io.liveCycles += 1
            runner.afterSample()
        }
        assertEquals(
            CarCommand.entries.size,
            io.events.count {
                it.first == "car_control" &&
                    it.second["outcome"] != "refused"
            },
        )
        for (command in io.commands) {
            assertTrue(
                "unexpected adapter command $command",
                command in CarControlRunner.CONFIG_COMMANDS || CarControlFrames.isAllowedTransmit(command),
            )
        }
        // The listen-only runner's command lists are untouched by car controls.
        assertTrue(SwcanListenRunner.SETUP_COMMANDS.none { it.startsWith("STPX") || it.startsWith("STCSWM") })
    }

    @Test
    fun flashHasNoReadbackAndIsSentUnconfirmed() {
        val sample = run(CarCommand.FLASH_LIGHTS)
        assertEquals("sent_unconfirmed", sample.getString("carControlLastOutcome"))
        assertTrue(sample.getString("carControlLastDetail").contains("no readable confirmation"))
    }

    @Test
    fun silentCarIsSentNotConfirmed() {
        val sample = run(CarCommand.UNLOCK)
        assertEquals("sent_unconfirmed", sample.getString("carControlLastOutcome"))
        assertTrue(sample.getString("carControlLastDetail").contains("did not report"))
    }

    @Test
    fun remoteStartConfirmedByStatusOrBlower() {
        io.monitorText = "10 39 00 40 02\rSTOPPED\r\r>"
        assertEquals("confirmed", run(CarCommand.REMOTE_START).getString("carControlLastOutcome"))
        now += 10_000L
        io.monitorText = "10 39 00 40 00\rSTOPPED\r\r>"
        val before = io.commands.size
        val stop = run(CarCommand.REMOTE_STOP)
        assertEquals("confirmed", stop.getString("carControlLastOutcome"))
        assertTrue("remote stop sends no wakeup", io.commands.drop(before).none { it == "STCSWM 2" })
    }

    @Test
    fun windowsGoToTheBcmOnHsCanAndReadBackOnSwcan() {
        io.monitorText = "10 64 A0 CB 36 36\rSTOPPED\r\r>"
        val sample = run(CarCommand.WINDOWS_DOWN)
        assertEquals("confirmed", sample.getString("carControlLastOutcome"))
        assertTrue(io.commands.contains("STP 31"))
        assertEquals(
            1 + CarControlFrames.WINDOW_REPEATS,
            io.stpx().count {
                it ==
                    CarControlFrames.BCM_WINDOWS_DOWN.toStpx()
            },
        )
        // Read-back switched back to SW-CAN before listening.
        val lastHs = io.commands.lastIndexOf("STP 31")
        assertTrue(io.commands.subList(lastHs, io.commands.size).containsAll(listOf("STP 61", "STM")))
    }

    @Test
    fun windowsClosingConfirmation() {
        fun confirmation(text: String) =
            CarControlRunner.readbackConfirmation(
                ControlReadback.WINDOWS_CLOSING,
                SwcanFrameDecoder.decodeAll(SwcanFrameDecoder.parseMonitorOutput(text)),
            )
        assertEquals("windows closing", confirmation("10 64 A0 CB 00 00\rSTOPPED\r\r>"))
        assertEquals("windows closing", confirmation("10 64 A0 CB 36 36\r10 64 A0 CB 24 24\rSTOPPED\r\r>"))
        assertNull(confirmation("10 64 A0 CB 36 36\rSTOPPED\r\r>"))
        assertNull(CarControlRunner.readbackConfirmation(ControlReadback.WINDOWS_OPENING, emptyList()))
        assertNull(CarControlRunner.readbackConfirmation(ControlReadback.NONE, emptyList()))
    }

    @Test
    fun blowerConfirmsRemoteStartOnlyWhenItKeepsRunning() {
        fun confirmation(vararg blower: Double) =
            CarControlRunner.readbackConfirmation(
                ControlReadback.REMOTE_START_ON,
                blower.map { SwcanReading(SwcanField.BLOWER, it) },
            )
        assertEquals("cabin blower running", confirmation(0.0, 40.0, 42.0))
        // One frame isn't proof: the fan coasts for a frame after the climate shuts off.
        assertNull(confirmation(40.0))
        assertNull(confirmation(27.0, 0.0))
        assertNull(confirmation())
    }

    @Test
    fun unconfirmedCommandIsRefusedWithoutTouchingTheAdapter() {
        val sample = run(CarCommand.LOCK, confirmed = false)
        assertEquals("refused", sample.getString("carControlLastOutcome"))
        assertEquals("not_confirmed", io.event("car_control")!!["reason"])
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun confirmationIsConsumedEvenWhenTheGateRefuses() {
        io.adapter = CarControlGate.Adapter.NOT_STN
        val sample = run(CarCommand.LOCK)
        assertEquals("refused", sample.getString("carControlLastOutcome"))
        assertEquals("adapter_not_stn", io.event("car_control")!!["reason"])
        assertTrue(io.confirmations.isEmpty())
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun movingCarIsRefused() {
        io.confirmations.add(CarCommand.UNLOCK)
        runner.observe(JSONObject().put("speedKph", 20.0).put("prndlState", "D").put("vehicleState", "driving_ev"), now)
        runner.request(CarCommand.UNLOCK)
        runner.afterSample()
        assertEquals("moving", io.event("car_control")!!["reason"])
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun disabledRefusesAtRequestAndAddsNothingToSamples() {
        io.enabled = false
        assertEquals("disabled", runner.request(CarCommand.LOCK))
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals(0, sample.length())
        runner.afterSample()
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun disabledBetweenRequestAndExecutionRefuses() {
        parked()
        io.confirmations.add(CarCommand.LOCK)
        runner.request(CarCommand.LOCK)
        io.enabled = false
        runner.afterSample()
        assertEquals("disabled", io.event("car_control")!!["reason"])
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun secondRequestWhilePendingIsBusyNotQueued() {
        parked()
        assertNull(runner.request(CarCommand.LOCK))
        assertEquals("busy", runner.request(CarCommand.UNLOCK))
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals("busy", sample.getString("carControlGate"))
        assertTrue(sample.getBoolean("carControlBusy"))
    }

    @Test
    fun staleRequestExpires() {
        parked()
        io.confirmations.add(CarCommand.LOCK)
        runner.request(CarCommand.LOCK)
        now += 15_001L
        parked()
        runner.afterSample()
        assertEquals("expired", io.event("car_control")!!["reason"])
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun rateLimitsBackToBackCommands() {
        run(CarCommand.FLASH_LIGHTS)
        val before = io.commands.size
        now += 1_000L
        val sample = run(CarCommand.FLASH_LIGHTS)
        assertEquals("refused", sample.getString("carControlLastOutcome"))
        assertEquals("rate_limited", io.event("car_control")!!["reason"])
        assertEquals(before, io.commands.size)
    }

    @Test
    fun gateStateIsPublishedOnSamples() {
        parked()
        val sample = JSONObject()
        runner.appendTo(sample, now)
        assertEquals("ready", sample.getString("carControlGate"))
        assertEquals("", sample.getString("carControlGateDetail"))
        assertFalse(sample.has("carControlLastOutcome"))
        io.adapter = CarControlGate.Adapter.STN_UNVERIFIED
        runner.appendTo(sample, now)
        assertEquals("swcan_unverified", sample.getString("carControlGate"))
    }

    @Test
    fun adapterOffHsCanRefusesWithoutSending() {
        io.replies["ATDPN"] = "A7\r\r>"
        val sample = run(CarCommand.LOCK)
        assertEquals("refused", sample.getString("carControlLastOutcome"))
        assertEquals(listOf("ATDPN"), io.commands)
        assertEquals("hs_protocol_7", io.event("car_control")!!["reason"])
    }

    @Test
    fun setupFailureSendsNothingButStillRestores() {
        io.replies["ATV1"] = "?\r\r>"
        val sample = run(CarCommand.LOCK)
        assertEquals("failed", sample.getString("carControlLastOutcome"))
        assertTrue(sample.getString("carControlLastDetail").startsWith("Nothing was sent"))
        assertTrue(io.stpx().isEmpty())
        assertTrue(io.commands.containsAll(CarControlRunner.RESTORE_COMMANDS))
        assertEquals(0, io.reinitCount)
    }

    @Test
    fun failedTransmitAfterRequestStillSendsTheRelease() {
        // 0 wake, 1 BCM wake, 2 lock request, 3 flash fails.
        io.failTransmitAt = 3
        val sample = run(CarCommand.LOCK)
        assertEquals("failed", sample.getString("carControlLastOutcome"))
        assertEquals(CarControlFrames.TELEMATICS_RELEASE.toStpx(), io.stpx().last())
        assertEquals("4", io.event("car_control")!!["framesSent"])
    }

    @Test
    fun stoppedSessionMidCommandFails() {
        io.pauseOk = false
        val sample = run(CarCommand.REMOTE_START)
        assertEquals("failed", sample.getString("carControlLastOutcome"))
        assertTrue(sample.getString("carControlLastDetail").contains("session stopped"))
    }

    @Test
    fun monitorWithoutPromptFails() {
        io.monitorPrompt = false
        val sample = run(CarCommand.FLASH_LIGHTS)
        assertEquals("failed", sample.getString("carControlLastOutcome"))
        assertEquals(CarControlFrames.TELEMATICS_RELEASE.toStpx(), io.stpx().last())
    }

    @Test
    fun failedRestoreForcesReinit() {
        io.dpnQueue.addAll(listOf("A6\r\r>", "A61\r\r>"))
        run(CarCommand.FLASH_LIGHTS)
        assertEquals(1, io.reinitCount)
        assertEquals("restore_failed", io.event("car_control_hs_reinit")!!["reason"])
        assertEquals("false", io.event("car_control")!!["restored"])
    }

    @Test
    fun noLiveDataAfterCommandForcesReinitOnce() {
        run(CarCommand.FLASH_LIGHTS)
        runner.afterSample()
        assertEquals(1, io.reinitCount)
        runner.afterSample()
        assertEquals(1, io.reinitCount)
    }

    @Test
    fun liveDataAfterCommandNeedsNoReinit() {
        run(CarCommand.FLASH_LIGHTS)
        io.liveCycles += 1
        runner.afterSample()
        assertEquals(0, io.reinitCount)
    }

    @Test
    fun refuseOutsideSessionIsRecorded() {
        runner.refuseOutsideSession(CarCommand.LOCK, "no_live_session", "Connect first.")
        assertEquals("refused", runner.lastResult()!!.outcome)
        assertEquals("no_live_session", io.event("car_control")!!["reason"])
    }

    @Test
    fun resetSessionDropsThePendingRequest() {
        parked()
        io.confirmations.add(CarCommand.LOCK)
        runner.request(CarCommand.LOCK)
        runner.resetSession()
        runner.afterSample()
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun transmitOkRecognisesAdapterErrors() {
        assertTrue(CarControlRunner.transmitOk("\r>"))
        assertTrue(CarControlRunner.transmitOk("OK\r\r>"))
        assertTrue(CarControlRunner.transmitOk(null))
        for (bad in listOf("?", "CAN ERROR", "BUS OFF", "UNABLE TO CONNECT", "BUFFER FULL", "LV RESET")) {
            assertFalse(bad, CarControlRunner.transmitOk("$bad\r\r>"))
        }
    }
}
