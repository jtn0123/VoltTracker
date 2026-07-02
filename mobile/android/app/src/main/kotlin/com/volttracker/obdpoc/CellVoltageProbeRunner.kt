package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Dedicated 96-cell voltage snapshot pass on the BECM (ATSH7E7), triggered by the
 * detail-probe channel with stage [EnhancedPidProfiles.STAGE_CELLS].
 *
 * Both Volt generations have 96 series cell groups, but the DID layout differs
 * (see docs/sensor-expansion-plan-2026-06-09.md, Batch 3): cells 1-31 answer at
 * 0x4181-0x419F on both layouts; the Bolt-style layout continues contiguously
 * (cells 32-96 at 0x41A0-0x41E0) while the Gen 1 layout jumps to 0x4200-0x4240.
 * The runner reads the shared prefix, then picks the tail layout by whether the
 * first contiguous DID (0x41A0) decodes to a plausible cell voltage.
 *
 * A snapshot is persisted (one battery_snapshots parent + one cell_snapshots row
 * per answering cell) only when at least [MIN_VALID_CELLS] cells decoded, so a
 * wrong layout or an asleep BECM can't write a junk snapshot.
 */
class CellVoltageProbeRunner(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    @Throws(IOException::class)
    fun run() {
        service.broadcastStatus(
            "scanning",
            "Reading all 96 battery cell voltages. Keep the car awake; this takes up to a minute.",
            false,
        )
        service.updateNotification("Cell voltage probe on ${service.activeName}")

        val raw = StringBuilder()
        ObdElmDecode.appendProbeLine(raw, "adapter", service.activeName)
        ObdElmDecode.appendProbeLine(raw, "stage", EnhancedPidProfiles.STAGE_CELLS)
        probeCommand("ATSH7E7", raw)

        val voltages = arrayOfNulls<Double>(CELL_COUNT)
        // Cells 1-31 sit at the same DIDs in both known layouts.
        for (cell in 1..SHARED_PREFIX_CELLS) {
            voltages[cell - 1] = readCell(cellCommandBolt(cell), raw)
        }
        // Layout pick: does the first contiguous (Bolt-style) tail DID answer?
        val boltCell32 = readCell(cellCommandBolt(32), raw)
        if (boltCell32 != null) {
            ObdElmDecode.appendProbeLine(raw, "layout", "contiguous 0x41A0-0x41E0")
            voltages[31] = boltCell32
            for (cell in 33..CELL_COUNT) {
                voltages[cell - 1] = readCell(cellCommandBolt(cell), raw)
            }
        } else {
            ObdElmDecode.appendProbeLine(raw, "layout", "gen1 0x4200-0x4240")
            for (cell in 32..CELL_COUNT) {
                voltages[cell - 1] = readCell(cellCommandGen1(cell), raw)
            }
        }

        val validCount = voltages.count { it != null }
        val persisted =
            if (validCount >= MIN_VALID_CELLS) {
                persistSnapshot(voltages)
            } else {
                false
            }
        broadcastProbeSample(voltages, validCount, raw)

        val summary =
            when {
                validCount >= MIN_VALID_CELLS ->
                    "Cell probe complete: $validCount of $CELL_COUNT cells read." +
                        if (persisted) " Map saved on the Battery tab." else ""
                validCount > 0 ->
                    "Cell probe answered only $validCount of $CELL_COUNT cells - not enough for a trustworthy map. " +
                        "Try again with the car on."
                else -> "The battery module did not answer the cell-voltage probe. Try again with the car on."
            }
        service.broadcastStatus("scan-complete", summary, false)
        service.updateNotification("Cell probe complete for ${service.activeName}")
    }

    @Throws(IOException::class)
    private fun readCell(
        command: String,
        raw: StringBuilder,
    ): Double? {
        val response = probeCommand(command, raw)
        // Header-aware decode: on ATSH7E7 every DID in the cell layout is a cell voltage, even the
        // ones whose command string collides with a 7E4 PID (e.g. "2241A3" = cell 35 here).
        return ObdProtocol.parseCellVoltageProbe(command, response)?.valueNumeric
    }

    @Throws(IOException::class)
    private fun probeCommand(
        command: String,
        raw: StringBuilder,
    ): String {
        val response = engine.sendRecoverableCommand(command, PROBE_TIMEOUT_MS)
        ObdElmDecode.appendProbeLine(raw, command, ObdElmDecode.summarizeForStorage(command, response))
        return response
    }

    private fun persistSnapshot(voltages: Array<Double?>): Boolean {
        val store = service.localStore ?: return false
        return try {
            store.recordCellSnapshot(voltages.toList()) > 0
        } catch (ex: RuntimeException) {
            service.recorder.logError("cell_snapshot_persist_failed", ex)
            false
        }
    }

    private fun broadcastProbeSample(
        voltages: Array<Double?>,
        validCount: Int,
        raw: StringBuilder,
    ) {
        val sample = JSONObject()
        try {
            sample.put("source", "cell-probe")
            sample.put("connected", true)
            sample.put("adapter", service.activeName)
            sample.put("updatedAt", System.currentTimeMillis())
            if (validCount > 0) {
                val cells = JSONArray()
                for (voltage in voltages) {
                    cells.put(voltage ?: JSONObject.NULL)
                }
                sample.put("cellVoltages", cells)
            }
            sample.put("raw", ObdElmDecode.tail(raw.toString(), 7200))
        } catch (ignored: JSONException) {
            // Local values are safe.
        }
        service.broadcastTelemetry(sample)
    }

    companion object {
        const val CELL_COUNT: Int = 96

        /** Cells 1-31 share DIDs 0x4181-0x419F across both known layouts. */
        private const val SHARED_PREFIX_CELLS = 31

        /** Persist only when at least this many cells decoded, so a wrong layout can't save junk. */
        const val MIN_VALID_CELLS: Int = 90

        private const val PROBE_TIMEOUT_MS = 1800L

        /** Contiguous (Bolt-style) layout: cell N at DID 0x4180+N for N in 1..96. */
        internal fun cellCommandBolt(cell: Int): String = mode22Command(0x4180 + cell)

        /** Gen 1 tail layout: cell N at DID 0x4200+(N-32) for N in 32..96. */
        internal fun cellCommandGen1(cell: Int): String = mode22Command(0x4200 + cell - 32)

        private fun mode22Command(did: Int): String = String.format(Locale.US, "22%04X", did)
    }
}
