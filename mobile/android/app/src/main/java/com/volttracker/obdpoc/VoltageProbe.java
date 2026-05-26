package com.volttracker.obdpoc;

import java.io.IOException;
import java.util.Locale;

/**
 * Bucket 2 (B7): one-shot Mode 01 PID 42 probe after a successful ELM327 init.
 *
 * <p>Captures the OBD control-module voltage so the dashboard's low-voltage hint (Bucket 4b's C9)
 * has a real number to render and the session summary records adapter-side battery state at the
 * moment we managed to bring the link up. Runs exactly once per successful init — the engine's
 * normal {@code LIVE_PROBES} loop will keep refreshing the value during the session, but having the
 * probe here means we get the reading even on sessions that never reach steady-state polling.
 *
 * <p>The decoding lives here instead of {@link ObdProtocol} because the value flows directly into
 * {@link ObdService#setLastVoltage(double)} as a service-level signal, not into the per-sample
 * telemetry JSON; keeping it isolated avoids accidentally widening the protocol decoder surface.
 *
 * <p>Coupling: the engine owns the connection, so this class can't open a socket on its own.
 * Instead {@link ObdService#maybeRunVoltageProbe(ObdPollingEngine)} hands us a small functional
 * interface ({@link Sender}) backed by the engine's package-private {@code transactOneShot} — one
 * method, one round-trip, no coupling to the engine's other state.
 */
final class VoltageProbe {

    /**
     * Mode 01 PID 42 — "Control module voltage" (SAE J1979). Two-byte response, (A*256+B)/1000 V.
     */
    static final String PID = "0142";

    // Voltage is a low-priority post-init probe — it blocks the engine thread synchronously and
    // delays the first live-data PID, so we don't want a wedged adapter to cost us multiple
    // seconds of latency on every connect. 1 s is plenty for an ELM327 to answer a single PID;
    // missing the reply just means lastVoltage stays null and the dashboard's low-voltage hint
    // (C9) stays hidden — no correctness impact.
    static final long DEFAULT_TIMEOUT_MS = 1000L;

    /** Functional handle to the engine's IO without exposing the full engine surface. */
    @FunctionalInterface
    interface Sender {
        String transactOneShot(String command, long timeoutMs) throws IOException;
    }

    private final ObdService service;

    VoltageProbe(ObdService service) {
        this.service = service;
    }

    /**
     * Runs the one-shot PID-42 probe. Logs {@code control_module_voltage} with {@code volts} and
     * {@code atMs} on success; logs {@code control_module_voltage_failed} with a {@code reason} on
     * any error or undecodeable response. Never throws — a probe failure must not bubble up into
     * the engine's init path and trigger a reconnect.
     */
    void run(Sender sender) {
        if (sender == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        String response;
        try {
            response = sender.transactOneShot(PID, DEFAULT_TIMEOUT_MS);
        } catch (IOException ex) {
            if (service.recorder != null) {
                service.recorder.logEvent(
                        "control_module_voltage_failed",
                        "reason",
                        "io_error",
                        "message",
                        ex.getMessage() == null ? "" : ex.getMessage());
            }
            return;
        } catch (RuntimeException ex) {
            if (service.recorder != null) {
                service.recorder.logEvent(
                        "control_module_voltage_failed",
                        "reason",
                        "runtime_error",
                        "message",
                        ex.getMessage() == null ? "" : ex.getMessage());
            }
            return;
        }
        Double volts = parseVolts(response);
        if (volts == null) {
            if (service.recorder != null) {
                service.recorder.logEvent(
                        "control_module_voltage_failed",
                        "reason",
                        "decode_failed",
                        "raw",
                        ObdProtocol.summarize(response));
            }
            return;
        }
        long now = System.currentTimeMillis();
        service.setLastVoltage(volts);
        if (service.recorder != null) {
            service.recorder.logEvent(
                    "control_module_voltage",
                    "volts",
                    String.format(Locale.US, "%.3f", volts),
                    "atMs",
                    String.valueOf(now),
                    "elapsedMs",
                    String.valueOf(now - startedAt));
        }
    }

    /**
     * Decodes a raw {@code 0142} response per SAE J1979: looks for the {@code 4142} header byte,
     * then reads the next two bytes as a big-endian unsigned 16-bit voltage in millivolts. Returns
     * {@code null} when the response is missing, lacks the header, or doesn't carry two payload
     * bytes (e.g. "NO DATA" or a search-in-progress frame).
     *
     * <p>Visible for testing.
     */
    static Double parseVolts(String response) {
        if (response == null) {
            return null;
        }
        String hex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        int index = hex.indexOf("4142");
        if (index < 0) {
            return null;
        }
        int dataStart = index + 4;
        if (hex.length() < dataStart + 4) {
            return null;
        }
        try {
            int a = Integer.parseInt(hex.substring(dataStart, dataStart + 2), 16);
            int b = Integer.parseInt(hex.substring(dataStart + 2, dataStart + 4), 16);
            double volts = (a * 256.0 + b) / 1000.0;
            // Plausibility: the OBD-II port carries the LV battery, so legitimate readings span
            // roughly 0 V (totally dead battery) to ~16 V (engine running, alternator charging).
            // Anything wildly outside is a parse error. We deliberately keep the lower bound at
            // 0 — the C9 low-voltage hint is most useful exactly when the battery is dying.
            if (volts < 0.0 || volts > 32.0) {
                return null;
            }
            return volts;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
