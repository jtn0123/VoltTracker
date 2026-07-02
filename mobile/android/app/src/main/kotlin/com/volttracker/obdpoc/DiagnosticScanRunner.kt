package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Runs the one-shot diagnostic probe sweep (protocol detection, DTC + freeze-frame, VIN,
 * capability/live-data probes, and Volt-specific HV/charger headers) on behalf of
 * [ObdPollingEngine].
 *
 * Owns no IO state of its own: command IO is dispatched back through the engine via
 * `ObdPollingEngine.sendRecoverableCommand` and [ObdPollingEngine.appendLocation], so the engine's
 * `ioLock`-guarded transport and per-command logging continue to apply unchanged. Runs on the
 * service IO thread.
 */
class DiagnosticScanRunner(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    // Generic Mode 03 codes seen during the sweep, deduped + upper-cased. Surfaced in the scan
    // telemetry as `dtcCodes` so the event-notification path (M1) can diff them against the previous
    // scan's set and alert on genuinely-new codes; the per-command DTC persistence is unchanged.
    private val dtcCodes = LinkedHashSet<String>()

    @Throws(IOException::class)
    fun run() = run(DiagnosticScanProfile.FULL)

    @Throws(IOException::class)
    fun run(profile: DiagnosticScanProfile) {
        val full = profile == DiagnosticScanProfile.FULL
        dtcCodes.clear()
        service.broadcastStatus(
            "scanning",
            if (full) {
                "Running protocol, DTC, freeze-frame, VIN, and live-data probes..."
            } else {
                "Running a quick trouble-code scan (protocol, VIN, and stored codes)..."
            },
            false,
        )
        service.updateNotification("Scanning ${service.activeName}")

        val raw = StringBuilder()
        ObdElmDecode.appendProbeLine(raw, "adapter", service.activeName)
        probeCommand("ATI", 1800, raw)
        probeCommand("ATDP", 1800, raw)
        probeCommand("ATDPN", 1800, raw)
        probeCommand("ATRV", 1800, raw)

        publishProgress("Checking standard OBD protocols, capability pages, and VIN...")
        var vinResponse: String? = null
        for (protocol in ObdProbes.PROTOCOL_PROBES) {
            probeCommand(protocol, 1800, raw)
            for (capability in ObdProbes.CAPABILITY_PROBES) {
                probeCommand(capability, if (capability == "0100") 9000 else 3500, raw)
            }
            val thisVin = probeCommand("0902", 6000, raw)
            if (vinResponse == null && ObdProtocol.parseVin(thisVin) != null) {
                vinResponse = thisVin
            }
            probeCommand("03", 3500, raw)
        }

        ObdElmDecode.appendProbeLine(
            raw,
            "volt-discovery",
            "restore auto protocol for live + Volt probes",
        )
        probeCommand("ATSP0", 1800, raw)
        probeCommand("0100", 9000, raw)

        publishProgress(
            if (full) {
                "Reading DTCs, freeze frames, and live-data probes..."
            } else {
                "Reading stored diagnostic trouble codes..."
            },
        )
        ObdElmDecode.appendProbeLine(
            raw,
            "standard-diagnostics",
            if (full) "generic DTC and freeze-frame probes" else "generic DTC probes",
        )
        collectDtcCodes("03", probeCommand("03", 3500, raw))
        collectDtcCodes("07", probeCommand("07", 3500, raw))
        collectDtcCodes("0A", probeCommand("0A", 3500, raw))

        // FULL-only deep sweep: freeze frames, live data, and the slow Volt HV / charger /
        // transmission / brake / TPMS discovery headers. A QUICK scan stops after the generic
        // DTC reads above so a stored-code check returns in seconds.
        if (full) {
            runDeepProbes(raw)
        }
        probeCommand("ATSH7DF", 1800, raw)

        if (vinResponse != null) {
            val vin = ObdProtocol.parseVin(vinResponse)
            val store = service.localStore
            if (vin != null && store != null) {
                try {
                    store.upsertVehicleFromVin(vin)
                } catch (ex: RuntimeException) {
                    service.recorder?.logError("vin_persist_failed", ex)
                }
            }
        }

        val sample = JSONObject()
        try {
            sample.put("source", "scan")
            sample.put("connected", true)
            sample.put("adapter", service.activeName)
            sample.put("updatedAt", System.currentTimeMillis())
            engine.appendLocation(sample)
            sample.put("dtcCodes", JSONArray(dtcCodes.toList()))
            sample.put("scanProfile", profile.wireName)
            sample.put("raw", ObdElmDecode.tail(raw.toString(), 7200))
        } catch (_: JSONException) {
            // Local values are safe.
        }
        service.broadcastTelemetry(sample)
        service.broadcastStatus(
            "scan-complete",
            if (full) {
                "Diagnostic scan complete. You can disconnect and bring the phone back for the log."
            } else {
                "Quick scan complete. Run a full scan for battery, module, and TPMS detail."
            },
            false,
        )
        service.updateNotification("Scan complete for ${service.activeName}")
    }

    /**
     * The FULL-depth probe stages skipped by a QUICK scan: Mode-02 freeze frames, the live-data
     * probe set, and every Volt-specific HV / charger / powertrain / transmission / brake / TPMS
     * discovery header.
     */
    @Throws(IOException::class)
    private fun runDeepProbes(raw: StringBuilder) {
        probeCommand("0200", 3500, raw)
        probeCommand("0202", 3500, raw)
        probeCommand("0204", 3200, raw)
        probeCommand("0205", 3200, raw)
        probeCommand("020C", 3200, raw)
        probeCommand("020D", 3200, raw)
        probeCommand("0211", 3200, raw)
        probeCommand("0242", 3200, raw)

        for (probe in ObdProbes.LIVE_PROBES) {
            probeCommand(probe, 3200, raw)
        }

        publishProgress("Reading Volt battery and charger modules...")
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E4 battery and charger probes")
        probeCommand("ATSH7E4", 1800, raw)
        for (probe in ObdProbes.VOLT_7E4_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E1 pack voltage and current probes")
        probeCommand("ATSH7E1", 1800, raw)
        for (probe in ObdProbes.VOLT_7E1_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        publishProgress("Reading Volt powertrain, transmission, and brake modules...")
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E0 maintenance and engine probes")
        probeCommand("ATSH7E0", 1800, raw)
        for (probe in ObdProbes.VOLT_7E0_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E2 transmission probes")
        probeCommand("ATSH7E2", 1800, raw)
        for (probe in ObdProbes.VOLT_7E2_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E6 brake-module probes")
        probeCommand("ATSH7E6", 1800, raw)
        for (probe in ObdProbes.VOLT_7E6_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        ObdElmDecode.appendProbeLine(raw, "volt-discovery", "ATSH7E7 BECM cell-interface layout probes")
        probeCommand("ATSH7E7", 1800, raw)
        for (probe in ObdProbes.VOLT_7E7_LAYOUT_PROBES) {
            probeCommand(probe, 4200, raw)
        }
        publishProgress("Finishing optional TPMS discovery probes...")
        if (ObdProbes.TPMS_7E0_DISCOVERY_PROBES.isNotEmpty()) {
            ObdElmDecode.appendProbeLine(raw, "tpms-discovery", "ATSH7E0 candidate tire-pressure probes")
            probeCommand("ATSH7E0", 1800, raw)
            for (probe in ObdProbes.TPMS_7E0_DISCOVERY_PROBES) {
                probeCommand(probe, 4200, raw)
            }
        }
        if (ObdProbes.TPMS_760_DISCOVERY_PROBES.isNotEmpty()) {
            ObdElmDecode.appendProbeLine(raw, "tpms-discovery", "ATSH760 candidate TPMS receiver probes")
            probeCommand("ATSH760", 1800, raw)
            for (probe in ObdProbes.TPMS_760_DISCOVERY_PROBES) {
                probeCommand(probe, 4200, raw)
            }
        }
    }

    @Throws(IOException::class)
    private fun probeCommand(
        command: String,
        timeoutMs: Long,
        raw: StringBuilder,
    ): String {
        val response = engine.sendRecoverableCommand(command, timeoutMs)
        ObdElmDecode.appendProbeLine(raw, command, ObdElmDecode.summarizeForStorage(command, response))
        return response
    }

    private fun publishProgress(detail: String) {
        service.broadcastStatus("scanning", detail, false)
        service.updateNotification(detail)
    }

    /** Parses generic DTCs from a Mode 03/07/0A response and folds them into [dtcCodes]. */
    private fun collectDtcCodes(
        command: String,
        response: String,
    ) {
        val parsed = ObdProtocol.parseDiagnosticTroubleCodes(command, response, "7DF")
        for (dtc in parsed) {
            val code = dtc.code.trim().uppercase()
            if (code.isNotEmpty()) {
                dtcCodes.add(code)
            }
        }
    }
}
