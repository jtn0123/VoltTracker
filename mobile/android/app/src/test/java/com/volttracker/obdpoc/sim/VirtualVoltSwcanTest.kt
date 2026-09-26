package com.volttracker.obdpoc.sim

import android.content.Intent
import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Mode
import com.volttracker.obdpoc.sim.VirtualVoltScorecardTest.VirtualVoltService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Full-path test of the listen-only SW-CAN window: the real service → polling engine →
 * [SwcanListenRunner] → live sample → TelemetryPayload, against a [VirtualVolt] that behaves like
 * an OBDLink (switches to SW-CAN on `STP 61`, and answers NO DATA to every OBD request until it is
 * switched back). Proves the broadcast values reach the dashboard payload AND that HS-CAN polling
 * keeps working around the windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VirtualVoltSwcanTest {
    private val controllers = mutableListOf<ServiceController<VirtualVoltService>>()

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
    }

    @Test
    fun obdLinkBroadcastsReachTheDashboardInEveryMode() {
        for (mode in Mode.entries) {
            val (adapter, service) = drive(mode, stn = true)
            val expected = VirtualVoltCatalog.SWCAN_FRAMES.getValue(mode).flatMap { it.fields }
            val wire = synchronized(service.wirePayloads) { service.wirePayloads.toList() }
            val missing = expected.filterNot { field -> wire.any { it.has(field) } }
            assertTrue("$mode SW-CAN fields never reached the dashboard: $missing", missing.isEmpty())
            assertTrue("$mode: at least two listen windows", adapter.commands().count { it == "STM" } >= 2)
            val latest = wire.last { it.has("aux12vVoltage") }
            assertEquals(12.6, latest.getDouble("aux12vVoltage"), 1e-9)
            assertTrue(latest.has("aux12vStaleMs"))
        }
    }

    @Test
    fun hsPollingKeepsAnsweringAroundEveryWindow() {
        val (adapter, service) = drive(Mode.DRIVING, stn = true)
        val exchanges = synchronized(adapter.exchanges) { adapter.exchanges.toList() }
        // No OBD request may ever be sent while the adapter sits on SW-CAN.
        var onSwcan = false
        for (exchange in exchanges) {
            when {
                exchange.command == "STP61" -> onSwcan = true
                exchange.command == "ATSP6" || exchange.command == "ATZ" -> onSwcan = false
                onSwcan -> assertTrue("sent ${exchange.command} while on SW-CAN", isAdapterLocal(exchange.command))
            }
        }
        // The first OBD request after every window is answered, so the restore really put HS back.
        val windows = exchanges.indices.filter { exchanges[it].command == "STM" }
        assertTrue("windows ran", windows.size >= 2)
        for (index in windows) {
            val next = exchanges.drop(index + 1).firstOrNull { it.command.startsWith("01") } ?: continue
            assertTrue(
                "first ${next.command} after a window answered ${next.reply}",
                next.reply != "NO DATA" && next.reply.contains("41"),
            )
        }
        // …and no safety-net adapter reset was ever needed.
        assertTrue("no ATZ after the first window", exchanges.drop(windows.first()).none { it.command == "ATZ" })
        val wire = synchronized(service.wirePayloads) { service.wirePayloads.toList() }
        assertTrue(wire.takeLast(5).all { it.has("speedKph") })
    }

    @Test
    fun plainElm327NeverLeavesHsCan() {
        val (adapter, _) = drive(Mode.DRIVING, stn = false)
        val commands = adapter.commands()
        assertTrue("STI identity probe runs", commands.contains("STI"))
        val switched = commands.filter { it.startsWith("STP") || it == "STM" || it.startsWith("STCMM") }
        assertTrue("a non-STN adapter must never be switched: $switched", switched.isEmpty())
    }

    private fun VirtualVolt.commands(): List<String> = synchronized(exchanges) { exchanges.map { it.command } }

    private fun isAdapterLocal(command: String): Boolean = command.startsWith("AT") || command.startsWith("ST")

    private fun drive(
        mode: Mode,
        stn: Boolean,
    ): Pair<VirtualVolt, VirtualVoltService> {
        val adapter = VirtualVolt(mode, stn = stn)
        VirtualVoltService.nextConnection = adapter
        VirtualVoltService.nextSwcanPolicy =
            SwcanListenRunner.Policy(firstWindowDelayMs = 0L, intervalMs = 0L, listenMs = 0L, stopTimeoutMs = 0L)
        val controller = Robolectric.buildService(VirtualVoltService::class.java).create()
        controllers.add(controller)
        val service = controller.get()
        service.localStore!!.clearAllData()
        service.onStartCommand(connectIntent(service), 0, 1)
        waitFor("$mode drive to collect $SAMPLES samples") { service.engineSamples.size >= SAMPLES }
        service.running.set(false)
        waitFor("$mode adapter to close") { adapter.closeCalls.get() > 0 }
        return adapter to service
    }

    private fun connectIntent(service: VirtualVoltService): Intent =
        Intent(service, VirtualVoltService::class.java).apply {
            action = ObdService.ACTION_CONNECT
            putExtra(ObdService.EXTRA_ADDRESS, "AA:BB:CC:DD:EE:FF")
            putExtra(ObdService.EXTRA_NAME, "Virtual OBDLink")
        }

    private fun waitFor(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $label")
    }

    private companion object {
        const val SAMPLES = 40
        const val WAIT_TIMEOUT_MS = 60_000L
    }
}
