package com.volttracker.obdpoc.sim

import android.content.Context
import android.content.Intent
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.CarCommand
import com.volttracker.obdpoc.CarControlAuth
import com.volttracker.obdpoc.CarControlFrames
import com.volttracker.obdpoc.CarControlSettings
import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Mode
import com.volttracker.obdpoc.sim.VirtualVoltScorecardTest.VirtualVoltService
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * End-to-end car controls against the [VirtualVolt]: the real service receives
 * [ObdService.ACTION_CAR_CONTROL], the polling engine's gate decides, the runner transmits the
 * allowlisted OVMS frames with `STPX`, the virtual body changes state and broadcasts it on SW-CAN,
 * the read-back confirms it, and HS-CAN polling resumes. Also proves the refusals transmit nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VirtualVoltCarControlTest {
    private val controllers = mutableListOf<ServiceController<VirtualVoltService>>()

    @Before
    fun setUp() {
        CarControlAuth.resetForTest()
    }

    @After
    fun tearDown() {
        for (controller in controllers) {
            controller.get().running.set(false)
            try {
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // Robolectric can complain when destroying a foreground service in JVM-only tests.
            }
        }
        VirtualVoltService.nextConnection = null
        VirtualVoltService.nextSwcanPolicy = null
        CarControlAuth.resetForTest()
    }

    @Test
    fun unlockIsGatedSentConfirmedAndHsPollingResumes() {
        val (adapter, service) = start(Mode.CHARGING, stn = true)
        waitForGate(service, "ready")
        val result = command(service, CarCommand.UNLOCK)
        assertEquals("confirmed", result.getString("carControlLastOutcome"))
        assertEquals(false, adapter.body.locked)
        assertOnlyAllowlistedFramesSent(adapter)
        assertEquals(
            listOf(
                "61:100:",
                "61:621:00FFFFFFFFFF0000",
                "62:1024E097:0003FF",
                "62:1024E097:0C00FF",
                "62:1024E097:0000FF",
            ),
            adapter.transmitted.toList(),
        )
        assertHsPollingResumed(adapter, service)
        // The outcome and the read-back state reach the dashboard payload.
        val wire = synchronized(service.wirePayloads) { service.wirePayloads.toList() }
        assertTrue(wire.any { it.optString("carControlLastOutcome") == "confirmed" })
        assertTrue(wire.any { it.optString("doorLockState") == "unlocked" })
    }

    @Test
    fun remoteStartWakesTheBusAndIsConfirmedByTheStatusFrame() {
        val (adapter, service) = start(Mode.CHARGING, stn = true)
        waitForGate(service, "ready")
        val result = command(service, CarCommand.REMOTE_START)
        assertEquals("confirmed", result.getString("carControlLastOutcome"))
        assertTrue("wake frame went out under high-voltage wakeup", adapter.body.awake)
        assertEquals(true, adapter.body.remoteStart)
        assertOnlyAllowlistedFramesSent(adapter)
        val commands = adapter.commands()
        val hv = commands.indexOf("STCSWM2")
        assertTrue("high-voltage wakeup used", hv >= 0)
        assertEquals("STPXH:100,D:,R:0", commands.drop(hv + 1).first { it.startsWith("STPX") })
        assertTrue("transceiver back to normal", commands.drop(hv).contains("STCSWM3"))
        assertHsPollingResumed(adapter, service)
    }

    @Test
    fun windowsDownGoesToTheBcmOnHsCanAndIsReadBack() {
        val (adapter, service) = start(Mode.CHARGING, stn = true)
        waitForGate(service, "ready")
        val result = command(service, CarCommand.WINDOWS_DOWN)
        assertEquals("confirmed", result.getString("carControlLastOutcome"))
        assertEquals(true, adapter.body.windowsOpen)
        assertOnlyAllowlistedFramesSent(adapter)
        assertTrue(adapter.transmitted.any { it == "31:241:07AE3BFF01010101" })
        assertHsPollingResumed(adapter, service)
    }

    @Test
    fun movingCarIsRefusedAndNothingIsTransmitted() {
        val (adapter, service) = start(Mode.DRIVING, stn = true)
        waitForGate(service, "moving")
        val result = command(service, CarCommand.LOCK)
        assertEquals("refused", result.getString("carControlLastOutcome"))
        assertTrue(adapter.transmitted.isEmpty())
        assertTrue(adapter.commands().none { it.startsWith("STPX") })
    }

    @Test
    fun plainElm327IsRefusedWithAnExplanation() {
        val (adapter, service) = start(Mode.CHARGING, stn = false)
        waitForGate(service, "adapter_not_stn")
        val result = command(service, CarCommand.FLASH_LIGHTS)
        assertEquals("refused", result.getString("carControlLastOutcome"))
        assertTrue(result.getString("carControlLastDetail").contains("OBDLink"))
        assertTrue(adapter.commands().none { it.startsWith("STP") || it.startsWith("STCSWM") })
    }

    @Test
    fun unconfirmedCommandIsRefused() {
        val (adapter, service) = start(Mode.CHARGING, stn = true)
        waitForGate(service, "ready")
        val result = command(service, CarCommand.LOCK, confirm = false)
        assertEquals("refused", result.getString("carControlLastOutcome"))
        assertTrue(adapter.transmitted.isEmpty())
    }

    @Test
    fun commandWithoutALiveSessionIsRefusedAndNothingIsSent() {
        val (adapter, service) = start(Mode.CHARGING, stn = true, enabled = true, connect = false)
        CarControlAuth.confirm(CarCommand.LOCK, System.currentTimeMillis())
        service.onStartCommand(controlIntent(service, CarCommand.LOCK), 0, 2)
        assertTrue(adapter.exchanges.isEmpty())
        assertTrue(adapter.transmitted.isEmpty())
    }

    @Test
    fun disabledControlsAddNothingToTheSampleAndRefuse() {
        val (adapter, service) = start(Mode.CHARGING, stn = true, enabled = false)
        waitFor("samples") { service.engineSamples.size >= SAMPLES }
        CarControlAuth.confirm(CarCommand.LOCK, System.currentTimeMillis())
        service.onStartCommand(controlIntent(service, CarCommand.LOCK), 0, 2)
        val after = service.engineSamples.size
        waitFor("more samples") { service.engineSamples.size >= after + SAMPLES }
        val samples = synchronized(service.engineSamples) { service.engineSamples.toList() }
        assertTrue(samples.none { it.has("carControlGate") || it.has("carControlLastOutcome") })
        assertTrue(adapter.transmitted.isEmpty())
    }

    private fun assertOnlyAllowlistedFramesSent(adapter: VirtualVolt) {
        val stpx = adapter.commands().filter { it.startsWith("STPX") }
        assertFalse(stpx.isEmpty())
        val allowed = CarControlFrames.ALLOWED_STPX.map { it.uppercase().replace(" ", "") }.toSet()
        stpx.forEach { assertTrue("non-allowlisted transmit: $it", it in allowed) }
    }

    private fun assertHsPollingResumed(
        adapter: VirtualVolt,
        service: VirtualVoltService,
    ) {
        val exchanges = synchronized(adapter.exchanges) { adapter.exchanges.toList() }
        val lastTransmit = exchanges.indexOfLast { it.command.startsWith("STPX") }
        // No OBD request while the adapter was off HS-CAN for the command.
        val firstSwitch = exchanges.indexOfFirst { it.command.startsWith("STPX") }
        val commandStart = exchanges.subList(0, firstSwitch).indexOfLast { it.command == "STCMM0" }
        exchanges.subList(commandStart, lastTransmit).forEach {
            assertTrue("sent ${it.command} mid-command", it.command.startsWith("AT") || it.command.startsWith("ST"))
        }
        val next = exchanges.drop(lastTransmit + 1).firstOrNull { it.command.startsWith("01") }
        assertTrue("an OBD request followed the command", next != null)
        assertTrue("first ${next!!.command} after the command answered ${next.reply}", next.reply.contains("41"))
        assertTrue("no adapter reset needed", exchanges.drop(lastTransmit).none { it.command == "ATZ" })
        val count = service.engineSamples.size
        waitFor("polling to continue") { service.engineSamples.size >= count + 5 }
        val tail = synchronized(service.engineSamples) { service.engineSamples.takeLast(5) }
        assertTrue(tail.all { it.has("speedKph") })
    }

    private fun command(
        service: VirtualVoltService,
        command: CarCommand,
        confirm: Boolean = true,
    ): JSONObject {
        val before = service.engineSamples.size
        if (confirm) CarControlAuth.confirm(command, System.currentTimeMillis())
        service.onStartCommand(controlIntent(service, command), 0, 2)
        var result: JSONObject? = null
        waitFor("$command outcome") {
            result =
                synchronized(service.engineSamples) {
                    service.engineSamples.drop(before).lastOrNull {
                        it.optString("carControlLastCommand") == command.wireName
                    }
                }
            result != null
        }
        return result!!
    }

    private fun waitForGate(
        service: VirtualVoltService,
        gate: String,
    ) = waitFor("gate $gate") {
        synchronized(service.engineSamples) { service.engineSamples.lastOrNull()?.optString("carControlGate") == gate }
    }

    private fun start(
        mode: Mode,
        stn: Boolean,
        enabled: Boolean = true,
        connect: Boolean = true,
    ): Pair<VirtualVolt, VirtualVoltService> {
        val adapter = VirtualVolt(mode, stn = stn)
        VirtualVoltService.nextConnection = adapter
        VirtualVoltService.nextSwcanPolicy =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 0L,
                intervalMs = 0L,
                listenMs = 0L,
                stopTimeoutMs = 0L,
                parkedIntervalMs = 0L,
                parkedListenMs = 0L,
            )
        val controller = Robolectric.buildService(VirtualVoltService::class.java).create()
        controllers.add(controller)
        val service = controller.get()
        service.localStore!!.clearAllData()
        val settings = CarControlSettings { service.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE) }
        if (enabled) assertTrue(settings.enable("2468")) else settings.disable()
        if (connect) service.onStartCommand(VirtualVoltTestSupport.connectIntent(service, "Virtual OBDLink"), 0, 1)
        return adapter to service
    }

    private fun VirtualVolt.commands(): List<String> = synchronized(exchanges) { exchanges.map { it.command } }

    private fun controlIntent(
        service: VirtualVoltService,
        command: CarCommand,
    ): Intent =
        Intent(service, VirtualVoltService::class.java).apply {
            action = ObdService.ACTION_CAR_CONTROL
            putExtra(ObdService.EXTRA_CAR_COMMAND, command.wireName)
        }

    private fun waitFor(
        label: String,
        condition: () -> Boolean,
    ) = VirtualVoltTestSupport.waitFor(label, WAIT_TIMEOUT_MS, condition)

    private companion object {
        const val SAMPLES = 40
        const val WAIT_TIMEOUT_MS = 60_000L
    }
}
