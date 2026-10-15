package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue
import com.volttracker.obdpoc.ObdProtocol.mode22Payload
import com.volttracker.obdpoc.ObdVoltMode22ParserRegistry.Mode22Parser
import java.util.Locale

/**
 * Gen 2 Volt status DIDs that decode to words rather than a number: bit fields (why the engine
 * started, how recent charges ended), small enums (charge state, battery conditioning, the charge
 * system's self-checks, how the car was last started), a flag (12 V maintenance charging), the
 * car's own charge timetable and the remote-start countdown.
 * The DIDs and their tables are GM's, as an established Volt app reads them on a 2017.
 * [ObdVoltMode22ParserRegistry] registers [parsers].
 */
internal object Gen2StatusDecoders {
    /** The five charge-record DIDs, in the car's order. */
    val CHARGE_RECORD_COMMANDS: List<String> = listOf("2243CB", "2243CC", "2243CD", "2243CE", "2243CF")

    const val NO_REASON = "None"

    fun parsers(): Map<String, Mode22Parser> =
        buildMap {
            put("222439", bits("engine start reason", ENGINE_START_REASONS))
            CHARGE_RECORD_COMMANDS.forEachIndexed { index, command ->
                put(command, bits("charge record ${index + 1}", CHARGE_RECORD_REASONS))
            }
            put("2243AE", enum("charge state", CHARGE_STATES))
            put("2243DE", enum("battery conditioning", CONDITIONING_MODES))
            put("224532", enum("charge system status", CHARGE_SYSTEM_STATES))
            put("224530", enum("charge system enable check", CHECK_RESULTS))
            put("22452F", enum("charge system control check", CHECK_RESULTS))
            put("22441E", flag("12V maintenance charge", MAINTENANCE_CHARGE_BIT))
            put("2283E1", weekTime("charge start time"))
            put("2283E2", weekTime("charge complete time"))
            put("228375", weekTime("120V charge start time"))
            put("228377", weekTime("120V charge complete time"))
            put("228537", remoteStartTimeLeft())
            put("22900D", enum("remote start reason", REMOTE_START_REASONS))
        }

    /**
     * Every set bit in the first `table.size` bytes adds its label; an all-clear field reads
     * [NO_REASON]. Bits with no label (reserved) are ignored. The numeric value is the label count.
     */
    private fun bits(
        name: String,
        table: List<List<String?>>,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            val payload = mode22Payload(response, command)
            if (payload == null || payload.size < table.size) {
                null
            } else {
                val labels =
                    table.flatMapIndexed { byteIndex, bitLabels ->
                        bitLabels.withIndex().mapNotNull { (bit, label) ->
                            label?.takeIf { payload[byteIndex] and (1 shl bit) != 0 }
                        }
                    }
                ParsedPidValue(name, labels.joinToString("; ").ifEmpty { NO_REASON }, labels.size.toDouble(), "")
            }
        }

    /** Byte A indexes [labels]; a code outside the table decodes to no value. */
    private fun enum(
        name: String,
        labels: List<String>,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            val code = mode22Payload(response, command)?.firstOrNull() ?: return@Mode22Parser null
            labels.getOrNull(code)?.let { ParsedPidValue(name, it, code.toDouble(), "") }
        }

    private fun flag(
        name: String,
        mask: Int,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            mode22Payload(response, command)?.firstOrNull()?.let { byte ->
                val on = byte and mask != 0
                ParsedPidValue(name, if (on) "Active" else "Off", if (on) 1.0 else 0.0, "")
            }
        }

    /**
     * The car's charge timetable: A = weekday (1 Sunday … 7 Saturday), B = hour, C = minute,
     * read as "Tue 23:05". The numeric value is minutes into the week from Sunday 00:00. A field
     * outside those ranges (no time scheduled) decodes to an empty text so it shows as no reading.
     */
    private fun weekTime(name: String): Mode22Parser =
        Mode22Parser { command, response ->
            val payload = mode22Payload(response, command)
            if (payload == null || payload.size < WEEK_TIME_BYTES) {
                null
            } else {
                val (day, hour, minute) = payload
                if (day !in 1..WEEKDAYS.size || hour !in 0 until HOURS || minute !in 0 until MINUTES) {
                    ParsedPidValue(name, "", null, "")
                } else {
                    val text = "%s %02d:%02d".format(Locale.US, WEEKDAYS[day - 1], hour, minute)
                    ParsedPidValue(name, text, ((day - 1) * HOURS * MINUTES + hour * MINUTES + minute).toDouble(), "")
                }
            }
        }

    /** The BCM's remote-start countdown: byte B in 10 s steps (byte A's meaning is unknown). */
    private fun remoteStartTimeLeft(): Mode22Parser =
        Mode22Parser { command, response ->
            mode22Payload(response, command)
                ?.getOrNull(1)
                ?.let { ObdProtocol.value("remote start time left", it * REMOTE_START_STEP_S, "s", 0) }
        }

    private const val REMOTE_START_STEP_S = 10.0
    private const val MAINTENANCE_CHARGE_BIT = 0x08
    private const val WEEK_TIME_BYTES = 3
    private const val HOURS = 24
    private const val MINUTES = 60
    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private val CHARGE_STATES = listOf("None", "Waiting", "Charging", "Complete", "Error")
    private val CONDITIONING_MODES =
        listOf("Off", "Active cooling", "Active heating", "Passive cooling", "Passive heating")
    private val CHARGE_SYSTEM_STATES = listOf("Not ready", "Ready", "Shutting down")
    private val CHECK_RESULTS = listOf("Passed", "Failed")
    private val REMOTE_START_REASONS =
        listOf("Ignition switch", "Remote start (power take-off)", "Remote start", "Not reporting")

    // Bytes A..D, each listing bits 0..7; null is a reserved bit.
    private val ENGINE_START_REASONS: List<List<String?>> =
        listOf(
            listOf(
                null,
                null,
                "Transmission request",
                "Reduced engine power",
                "Engine off too long",
                "Transmission temperature",
                null,
                "System fault",
            ),
            listOf(
                "Remote start",
                "Reverse",
                "Invalid data",
                "Speed too high",
                "Maintenance mode",
                "Low system voltage",
                null,
                "Battery temperature",
            ),
            listOf(
                "Acceleration demand",
                "Minimum run time",
                "Control function",
                "Ignition start",
                "Aux trans pump unavailable",
                "Inverter too hot",
                "Drive motor too hot",
                "Engine coolant temperature",
            ),
            listOf(
                "Battery voltage low",
                "Battery power low",
                "Battery module voltage low",
                "Battery charge low",
                "Transmission range",
                "Hood open",
                "Engine computer request",
                null,
            ),
        )

    private val CHARGE_RECORD_REASONS: List<List<String?>> =
        listOf(
            listOf(
                "Full charge",
                "Waiting for charge time",
                "Unplugged at the car",
                "Unplugged at the wall",
                "Autostart inhibit",
                "Utility inhibit",
                "Severe fault",
                "Moderate fault",
            ),
            listOf(
                "Minor fault",
                "Charge cord error",
                "Contactor open",
                "Cell voltage at max",
                "Plugged in repeatedly (30 s)",
                "Plugged in repeatedly (5 min)",
                null,
                null,
            ),
            listOf(
                null,
                "Battery protection",
                null,
                "12 V low while charging",
                "12 V low before charging",
                "Charger too hot",
                "Battery too cold",
                null,
            ),
            listOf("Charge time limit", null, null, null, null, null, null, null),
        )
}
