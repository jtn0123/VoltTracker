package com.volttracker.obdpoc

import com.volttracker.obdpoc.service.ObdService
import java.io.IOException
import java.util.Locale

/**
 * One-shot Mode 01 PID 42 probe after a successful ELM327 init.
 *
 * Captures the OBD control-module voltage so the dashboard's low-voltage hint has a real number to
 * render and the session summary records adapter-side battery state at the moment we managed to
 * bring the link up. Runs exactly once per successful init.
 */
class VoltageProbe(
    private val service: ObdService?,
) {
    /** Functional handle to the engine's IO without exposing the full engine surface. */
    fun interface Sender {
        @Throws(IOException::class)
        fun transactOneShot(
            command: String,
            timeoutMs: Long,
        ): String?
    }

    /**
     * Runs the one-shot PID-42 probe. Logs `control_module_voltage` with `volts` and `atMs` on
     * success; logs `control_module_voltage_failed` with a `reason` on any error or undecodeable
     * response. Never throws -- a probe failure must not bubble up into the engine's init path and
     * trigger a reconnect.
     */
    fun run(sender: Sender?) {
        sender ?: return
        val startedAt = System.currentTimeMillis()
        val response =
            try {
                sender.transactOneShot(PID, DEFAULT_TIMEOUT_MS)
            } catch (ex: IOException) {
                service?.recorder?.logEvent(
                    "control_module_voltage_failed",
                    "reason",
                    "io_error",
                    "message",
                    ex.message ?: "",
                )
                return
            } catch (ex: RuntimeException) {
                service?.recorder?.logEvent(
                    "control_module_voltage_failed",
                    "reason",
                    "runtime_error",
                    "message",
                    ex.message ?: "",
                )
                return
            }
        val volts = parseVolts(response)
        if (volts == null) {
            service?.recorder?.logEvent(
                "control_module_voltage_failed",
                "reason",
                "decode_failed",
                "raw",
                ObdProtocol.summarize(response),
            )
            return
        }
        val now = System.currentTimeMillis()
        service?.setLastVoltage(volts)
        service?.recorder?.logEvent(
            "control_module_voltage",
            "volts",
            String.format(Locale.US, "%.3f", volts),
            "atMs",
            now.toString(),
            "elapsedMs",
            (now - startedAt).toString(),
        )
    }

    companion object {
        /** Mode 01 PID 42 -- "Control module voltage" (SAE J1979). */
        const val PID: String = "0142"

        const val DEFAULT_TIMEOUT_MS: Long = 1000L

        /** Uses the same validity and responder selection as live telemetry. */
        @JvmStatic
        fun parseVolts(response: String?): Double? = ObdProtocol.parseControlModuleVoltage(response)
    }
}
