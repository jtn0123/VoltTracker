package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.appendProbeLine;
import static com.volttracker.obdpoc.ObdElmDecode.summarizeForStorage;
import static com.volttracker.obdpoc.ObdElmDecode.tail;

import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs the one-shot diagnostic probe sweep (protocol detection, DTC + freeze-frame, VIN,
 * capability/live-data probes, and Volt-specific HV/charger headers) on behalf of {@link
 * ObdPollingEngine}. Pulled out so the live-poll loop and the scan path can be read independently
 * and each file stays under the team's 500-LOC ceiling.
 *
 * <p>Owns no IO state of its own: command IO is dispatched back through the engine via {@link
 * ObdPollingEngine#sendRecoverableCommand(String, long)} and {@link
 * ObdPollingEngine#appendLocation(JSONObject)}, so the engine's {@code ioLock}-guarded transport
 * and per-command logging continue to apply unchanged. Runs on the service IO thread.
 */
final class DiagnosticScanRunner {

    private final ObdService service;
    private final ObdPollingEngine engine;

    DiagnosticScanRunner(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void run() throws IOException {
        service.broadcastStatus(
                "scanning",
                "Running protocol, DTC, freeze-frame, VIN, and live-data probes...",
                false);
        service.updateNotification("Scanning " + service.activeName);

        StringBuilder raw = new StringBuilder();
        appendProbeLine(raw, "adapter", service.activeName);
        probeCommand("ATI", 1800, raw);
        probeCommand("ATDP", 1800, raw);
        probeCommand("ATDPN", 1800, raw);
        probeCommand("ATRV", 1800, raw);

        String vinResponse = null;
        for (String protocol : ObdProbes.PROTOCOL_PROBES) {
            probeCommand(protocol, 1800, raw);
            for (String capability : ObdProbes.CAPABILITY_PROBES) {
                probeCommand(capability, "0100".equals(capability) ? 9000 : 3500, raw);
            }
            String thisVin = probeCommand("0902", 6000, raw);
            if (vinResponse == null && ObdProtocol.parseVin(thisVin) != null) {
                vinResponse = thisVin;
            }
            probeCommand("03", 3500, raw);
        }

        // The protocol sweep above leaves the adapter on the last probe (ATSP8), which is
        // the wrong CAN protocol for this car. Restore auto-detect so the live-data and
        // Volt PID probes below run on the vehicle's real protocol instead of CAN ERROR.
        appendProbeLine(raw, "volt-discovery", "restore auto protocol for live + Volt probes");
        probeCommand("ATSP0", 1800, raw);
        probeCommand("0100", 9000, raw);

        appendProbeLine(raw, "standard-diagnostics", "generic DTC and freeze-frame probes");
        probeCommand("03", 3500, raw);
        probeCommand("07", 3500, raw);
        probeCommand("0A", 3500, raw);
        probeCommand("0200", 3500, raw);
        probeCommand("0202", 3500, raw);
        probeCommand("0204", 3200, raw);
        probeCommand("0205", 3200, raw);
        probeCommand("020C", 3200, raw);
        probeCommand("020D", 3200, raw);
        probeCommand("0211", 3200, raw);
        probeCommand("0242", 3200, raw);

        for (String probe : ObdProbes.LIVE_PROBES) {
            probeCommand(probe, 3200, raw);
        }

        appendProbeLine(raw, "volt-discovery", "ATSH7E4 battery and charger probes");
        probeCommand("ATSH7E4", 1800, raw);
        for (String probe : ObdProbes.VOLT_7E4_PROBES) {
            probeCommand(probe, 4200, raw);
        }
        appendProbeLine(raw, "volt-discovery", "ATSH7E1 pack voltage and current probes");
        probeCommand("ATSH7E1", 1800, raw);
        for (String probe : ObdProbes.VOLT_7E1_PROBES) {
            probeCommand(probe, 4200, raw);
        }
        probeCommand("ATSH7DF", 1800, raw);

        // If any 0902 frame in the sweep yielded a parseable VIN, write the vehicle row off
        // the recorder thread so the vehicle-identity panel lights up on the next dashboard
        // refresh. We persist the redacted form only (last 4 chars + SHA-256 hash); see
        // ObdLocalStore.upsertVehicleFromVin for the redaction policy. Capture the store
        // reference at submit time so we don't NPE if the service is torn down mid-flight.
        if (vinResponse != null) {
            final String vin = ObdProtocol.parseVin(vinResponse);
            final com.volttracker.obdpoc.data.ObdLocalStore store = service.localStore;
            if (vin != null && store != null) {
                service.recorder.runAsync(() -> store.upsertVehicleFromVin(vin));
            }
        }

        JSONObject sample = new JSONObject();
        try {
            sample.put("source", "scan");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("updatedAt", System.currentTimeMillis());
            engine.appendLocation(sample);
            sample.put("raw", tail(raw.toString(), 7200));
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        service.broadcastTelemetry(sample);
        service.broadcastStatus(
                "scan-complete",
                "Diagnostic scan complete. You can disconnect and bring the phone back for the log.",
                false);
        service.updateNotification("Scan complete for " + service.activeName);
    }

    private String probeCommand(String command, long timeoutMs, StringBuilder raw)
            throws IOException {
        String response = engine.sendRecoverableCommand(command, timeoutMs);
        appendProbeLine(raw, command, summarizeForStorage(command, response));
        return response;
    }
}
