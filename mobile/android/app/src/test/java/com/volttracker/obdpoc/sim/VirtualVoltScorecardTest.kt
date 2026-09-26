package com.volttracker.obdpoc.sim

import android.content.Intent
import com.volttracker.obdpoc.PidSchedule
import com.volttracker.obdpoc.TelemetryPayload
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Evidence
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Mode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the real service → polling engine → LiveSampleReader → TelemetryPayload path against the
 * [VirtualVolt] and grades every scheduled PID: did the car answer, did the app decode it, and did
 * the value reach the dashboard payload. The graded table is written to
 * `app/build/reports/virtual-volt/scorecard.md`.
 *
 * The gate is deliberately narrow: a PID the car is known to answer ([Evidence.REAL] or
 * [Evidence.SEEN]) must reach the dashboard. [Evidence.GUESS] rows are reported but not gated,
 * because their bytes are invented and a miss there may be the guess, not the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VirtualVoltScorecardTest {
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
    }

    @Test
    fun catalogCoversEveryScheduledPid() {
        val missingFields =
            PidSchedule.SPECS.map { it.command }.filterNot(
                VirtualVoltCatalog.FIELDS_BY_COMMAND::containsKey,
            )
        val missingReplies = PidSchedule.SPECS.filter { VirtualVoltCatalog.find(headerOf(it), it.command) == null }
        assertTrue("PIDs missing from FIELDS_BY_COMMAND: $missingFields", missingFields.isEmpty())
        assertTrue(
            "PIDs missing from the virtual Volt catalog: ${missingReplies.map { it.command }}",
            missingReplies.isEmpty(),
        )
    }

    @Test
    fun knownGoodPidsReachTheDashboard() {
        val driving = drive(Mode.DRIVING)
        val charging = drive(Mode.CHARGING)
        writeReport(listOf(driving, charging))

        val broken = (driving.rows + charging.rows).filter { it.gated && !it.verdict.healthy }
        assertTrue(
            "PIDs the car answers but the dashboard never shows:\n" +
                broken.joinToString("\n") { "  ${it.mode} ${it.command} → ${it.verdict} (${it.fields})" },
            broken.isEmpty(),
        )
    }

    private fun drive(mode: Mode): Run {
        val adapter = VirtualVolt(mode)
        VirtualVoltService.nextConnection = adapter
        val controller = Robolectric.buildService(VirtualVoltService::class.java).create()
        controllers.add(controller)
        val service = controller.get()
        service.localStore!!.clearAllData()
        adapter.afterCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND) {
            if (service.engineSamples.size >= SAMPLES_PER_RUN) service.running.set(false)
        }

        service.onStartCommand(connectIntent(service), 0, 1)
        waitFor("$mode drive to collect $SAMPLES_PER_RUN samples") { service.engineSamples.size >= SAMPLES_PER_RUN }
        service.running.set(false)
        waitFor("$mode adapter to close") { adapter.closeCalls.get() > 0 }
        return Run(mode, grade(mode, adapter, service), adapter)
    }

    private fun grade(
        mode: Mode,
        adapter: VirtualVolt,
        service: VirtualVoltService,
    ): List<Row> {
        val exchanges = synchronized(adapter.exchanges) { adapter.exchanges.toList() }
        val engineSamples = synchronized(service.engineSamples) { service.engineSamples.toList() }
        val wirePayloads = synchronized(service.wirePayloads) { service.wirePayloads.toList() }
        return PidSchedule.SPECS.map { spec ->
            val header = headerOf(spec)
            val entry = VirtualVoltCatalog.find(header, spec.command)
            val sent = exchanges.filter { it.command == spec.command || isBatchContaining(it.command, spec.command) }
            val answered =
                entry != null &&
                    entry.evidence != Evidence.PLACEHOLDER &&
                    entry.replies[mode] != null &&
                    !isNegative(entry.replies[mode])
            val fields = VirtualVoltCatalog.FIELDS_BY_COMMAND.getValue(spec.command)
            val inEngine = fields.any { field -> engineSamples.any { it.has(field) } }
            val onWire = fields.any { field -> wirePayloads.any { it.has(field) } }
            val verdict =
                when {
                    sent.isEmpty() -> Verdict.NEVER_ASKED
                    !answered -> Verdict.CAR_HAS_NO_VALUE
                    onWire -> Verdict.SHOWN
                    inEngine -> Verdict.DROPPED_BEFORE_UI
                    else -> Verdict.NOT_DECODED
                }
            val gated =
                entry != null && answered && (entry.evidence == Evidence.REAL || entry.evidence == Evidence.SEEN)
            Row(mode, spec.command, header, spec.periodCycles, entry?.evidence, sent.size, fields, verdict, gated)
        }
    }

    private fun writeReport(runs: List<Run>) {
        val out = StringBuilder()
        out.append("# Virtual Volt scorecard\n\n")
        out.append(
            "Real service → engine → LiveSampleReader → TelemetryPayload, $SAMPLES_PER_RUN samples per mode.\n\n",
        )
        out.append("Evidence: REAL = bytes from this car · SEEN = car answers, bytes not kept · ")
        out.append(
            "DEAD = car refuses · PLACEHOLDER = car answers all-zero · GUESS = never observed, community formula.\n\n",
        )
        for (run in runs) {
            val counts = run.rows.groupingBy { it.verdict }.eachCount()
            out.append("## ${run.mode}\n\n")
            out.append(Verdict.entries.joinToString(" · ") { "${it.name} ${counts[it] ?: 0}" }).append("\n\n")
            val batchSends = run.adapter.exchanges.count { isBatchContaining(it.command, "010D") }
            val singleSpeedReads = run.adapter.exchanges.count { it.command == "010D" }
            out.append("Mode-01 batching: $batchSends combined requests, $singleSpeedReads single `010D` reads ")
            out.append(
                "(the virtual ECM answers combined requests in SAE J1979 form: one `41`, then PID/data pairs).\n\n",
            )
            out.append("| PID | Header | Every N cycles | Evidence | Sent | Fields | Verdict |\n")
            out.append("|---|---|---|---|---|---|---|\n")
            for (row in run.rows.sortedWith(compareBy({ it.verdict.healthy }, { it.verdict }, { it.command }))) {
                out.append("| ${row.command} | ${row.header} | ${row.period} | ${row.evidence} | ${row.sent} | ")
                out.append("${row.fields.joinToString(", ")} | ${row.verdict} |\n")
            }
            out.append("\n")
        }
        val report = File("build/reports/virtual-volt/scorecard.md")
        report.parentFile?.mkdirs()
        report.writeText(out.toString())
        println(out)
    }

    private fun connectIntent(service: VirtualVoltService): Intent =
        Intent(service, VirtualVoltService::class.java).apply {
            action = ObdService.ACTION_CONNECT
            putExtra(ObdService.EXTRA_ADDRESS, "AA:BB:CC:DD:EE:FF")
            putExtra(ObdService.EXTRA_NAME, "Virtual Volt")
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

    enum class Verdict(
        val healthy: Boolean,
    ) {
        NOT_DECODED(false),
        DROPPED_BEFORE_UI(false),
        NEVER_ASKED(false),
        CAR_HAS_NO_VALUE(true),
        SHOWN(true),
    }

    class Row(
        val mode: Mode,
        val command: String,
        val header: String,
        val period: Int,
        val evidence: Evidence?,
        val sent: Int,
        val fields: List<String>,
        val verdict: Verdict,
        val gated: Boolean,
    )

    class Run(
        val mode: Mode,
        val rows: List<Row>,
        val adapter: VirtualVolt,
    )

    class VirtualVoltEngine(
        service: VirtualVoltService,
        connection: VirtualVolt,
    ) : ObdPollingEngine(service, LoopSleeper { true }) {
        val openCount = AtomicInteger()

        init {
            setConnectionForTest(connection)
        }

        override fun isBluetoothReady(): Boolean = true

        override fun openBluetoothSocket(address: String?) {
            openCount.incrementAndGet()
        }
    }

    /** The real service, capturing what the engine produced and what went on the wire. */
    open class VirtualVoltService : ObdService() {
        val engineSamples: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        val wirePayloads: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())

        override fun createPollingEngine(): ObdPollingEngine =
            VirtualVoltEngine(this, nextConnection ?: error("Install a VirtualVolt before onCreate"))

        override fun broadcastTelemetry(payload: JSONObject?) {
            if (payload != null) engineSamples.add(JSONObject(payload.toString()))
            super.broadcastTelemetry(payload)
        }

        override fun broadcastTelemetry(telemetry: TelemetryPayload?) {
            telemetry?.toJson()?.let(wirePayloads::add)
            super.broadcastTelemetry(telemetry)
        }

        companion object {
            @Volatile
            var nextConnection: VirtualVolt? = null
        }
    }

    private companion object {
        // The slowest lane polls every 240 cycles; one full lap plus a margin covers every PID.
        const val SAMPLES_PER_RUN = 260
        const val WAIT_TIMEOUT_MS = 120_000L

        fun headerOf(spec: PidSchedule.PidSpec): String = spec.header.atCommand?.removePrefix("ATSH") ?: "7DF"

        fun isNegative(reply: String?): Boolean = reply?.startsWith("7F") == true

        fun isBatchContaining(
            sent: String,
            command: String,
        ): Boolean =
            command.length == 4 &&
                command.startsWith("01") &&
                sent.length > 4 &&
                sent.startsWith("01") &&
                sent.substring(2).chunked(2).contains(command.substring(2))
    }
}
