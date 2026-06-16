package com.volttracker.obdpoc

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

        /** Lower bound for a plausible decoded control-module voltage. */
        private const val MIN_PLAUSIBLE_VOLTS = 0.0

        /** Upper bound for a plausible decoded control-module voltage (load-bearing per tests). */
        private const val MAX_PLAUSIBLE_VOLTS = 32.0

        /**
         * Decodes a raw `0142` response per SAE J1979: looks for the `4142` header byte, then reads
         * the next two bytes as a big-endian unsigned 16-bit voltage in millivolts.
         */
        @JvmStatic
        fun parseVolts(response: String?): Double? {
            response ?: return null
            val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
            val index = hex.indexOf("4142")
            if (index < 0) {
                return null
            }
            val dataStart = index + 4
            if (hex.length < dataStart + 4) {
                return null
            }
            return try {
                val a = hex.substring(dataStart, dataStart + 2).toInt(16)
                val b = hex.substring(dataStart + 2, dataStart + 4).toInt(16)
                val volts = (a * 256.0 + b) / 1000.0
                if (volts < MIN_PLAUSIBLE_VOLTS || volts > MAX_PLAUSIBLE_VOLTS) {
                    null
                } else {
                    volts
                }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
