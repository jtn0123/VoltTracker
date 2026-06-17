package com.volttracker.obdpoc

/**
 * Tiered/staggered polling schedule for the OBD live-data loop.
 *
 * The Volt exposes values that change at very different rates — speed and RPM update many times per
 * second while in motion, while coolant temperature, battery temperature, and adapter voltage barely
 * move over tens of seconds. Polling every PID every cycle wastes adapter bandwidth and, more
 * visibly, slows down the values that *do* matter — speed and RPM end up bound by the time it takes
 * to ALSO read coolant temp, battery temp, etc.
 *
 * This schedule splits PIDs into lanes by how fast each value actually changes, then staggers the
 * slower lanes across consecutive cycles so the per-cycle cost stays roughly flat instead of having
 * "heavy" cycles every Nth iteration. Phase offsets are chosen to avoid stacking multiple slow
 * same-lane PIDs on the same cycle; the rare `ATSH 7E4` header switch is isolated to the
 * battery-temperature phase because `22434F` is the only PID on that header.
 *
 * Carry-forward semantics: when a PID is not polled on a given cycle, the polling engine reuses its
 * last-known raw response, so every sample published to the dashboard still contains every key (no
 * flicker).
 *
 * This class is pure data + arithmetic so it can be unit-tested on the JVM without any adapter or
 * Robolectric. [ObdPollingEngine] owns the actual I/O and consults [dueOnCycle] once per cycle.
 */
object PidSchedule {
    /** ELM header the PID lives behind. Only the non-broadcast headers require an ATSH switch. */
    enum class Header(
        /** ELM command that selects this header, or `null` for the default 7DF broadcast. */
        @JvmField val atCommand: String?,
    ) {
        BROADCAST(null),
        POWERTRAIN_7E0("ATSH7E0"),
        HV_PACK_7E1("ATSH7E1"),
        TRANSMISSION_7E2("ATSH7E2"),
        HV_PACK_7E4("ATSH7E4"),
        BRAKE_7E6("ATSH7E6"),
        CELL_BECM_7E7("ATSH7E7"),
    }

    /** Restore the broadcast header after a non-broadcast block of reads. */
    const val RESTORE_BROADCAST_HEADER_COMMAND = "ATSH7DF"

    /**
     * Hot-lane broadcast Mode-01 PIDs that get batched into a single request when the adapter
     * supports it. Listing both the full command strings (so the engine can match against
     * [PidSpec.command]) and the bare PID hex (so [ObdProtocol.buildMode01MultiCommand] can assemble
     * the batched request) avoids substring slicing in two places.
     */
    @JvmField
    val MODE_01_BATCH_COMMANDS: List<String> = listOf("010D", "010C", "0149")

    @JvmField
    val MODE_01_BATCH_PIDS_HEX: List<String> = listOf("0D", "0C", "49")

    /**
     * Polling spec for a single PID.
     *
     * @property command full ELM command, e.g. `010D` or `222414`.
     * @property header CAN header to select before polling [command].
     * @property periodCycles `1` means every cycle; higher values poll less often.
     * @property phaseOffset slot inside [periodCycles], used to spread slow PIDs across cycles.
     * @property conditional when `true`, this PID is only expected to answer in a specific vehicle
     *   mode (e.g. charger PIDs while charging), so it must be exempt from the live-poll negative-PID
     *   cache — a legitimate "NO DATA" while the car is driving must not disable it for the session.
     */
    class PidSpec(
        command: String?,
        header: Header?,
        @JvmField val periodCycles: Int,
        @JvmField val phaseOffset: Int,
        @JvmField val conditional: Boolean = false,
    ) {
        init {
            require(!command.isNullOrEmpty()) { "command must be non-empty" }
            require(header != null) { "header must be non-null" }
            require(periodCycles >= 1) { "periodCycles must be >= 1" }
            require(phaseOffset in 0 until periodCycles) {
                "phaseOffset must satisfy 0 <= phaseOffset < periodCycles, got " +
                    "$phaseOffset for period $periodCycles"
            }
        }

        @JvmField val command: String = command!!

        @JvmField val header: Header = header!!
    }

    // Full schedule. Keep PIDs of the same header grouped here so the diff between this list and
    // runtime header-grouping in ObdPollingEngine stays obvious.
    //
    // Volt-specific Mode-22 PIDs are field/community-derived rather than SAE-standard. Source
    // notes live in docs/volt-pid-research-2026-05-20.md and docs/volt-pids-community-sheet.csv;
    // decode formulas are centralized in ObdElmDecode.kt. Do not promote a new Mode-22 PID into
    // this live schedule until fresh target-car evidence exists.
    @JvmField
    val SPECS: List<PidSpec> =
        listOf(
            // --- Hot lane (every cycle) -- drive-critical, must stay snappy ------------------
            PidSpec("010D", Header.BROADCAST, 1, 0), // vehicle speed
            PidSpec("010C", Header.BROADCAST, 1, 0), // engine RPM
            // 0149 is the drive-by-wire accelerator pedal — the Volt returns a constant for 0111
            // because that PID is the ICE throttle body angle, not the pedal. We poll both and
            // prefer 0149 in the dashboard rendering when it's responding.
            PidSpec("0149", Header.BROADCAST, 1, 0), // accelerator pedal position D
            PidSpec("222414", Header.HV_PACK_7E1, 1, 0), // HV pack current
            // --- Warm lane (every 2 cycles) -- useful live context, but not speedometer-fast --
            PidSpec("0104", Header.BROADCAST, 2, 0), // engine load
            PidSpec("0111", Header.BROADCAST, 2, 1), // throttle position (ICE throttle body)
            PidSpec("222429", Header.HV_PACK_7E1, 2, 0), // HV pack voltage
            // --- Slow lane (every 6 cycles) -- changes slowly during normal driving ----------
            PidSpec("015B", Header.BROADCAST, 6, 0), // state of charge
            PidSpec("ATRV", Header.BROADCAST, 6, 3), // adapter voltage
            // --- Thermal lane (every 12 cycles) -- thermal mass, very slow change ------------
            PidSpec("0105", Header.BROADCAST, 12, 8), // coolant temp
            PidSpec("22434F", Header.HV_PACK_7E4, 12, 5), // HV battery temp
            PidSpec("010F", Header.BROADCAST, 24, 20), // intake air temp
            // --- Deep lane (every 24+ cycles) -- scan-validated context, not dashboard-fast ---
            PidSpec("0142", Header.BROADCAST, 24, 6), // control module voltage
            PidSpec("011F", Header.BROADCAST, 24, 12), // engine run time
            PidSpec("015C", Header.BROADCAST, 48, 36), // engine oil temp
            PidSpec("012F", Header.BROADCAST, 48, 30), // fuel level
            PidSpec("01A6", Header.BROADCAST, 240, 186), // odometer, if supported
            PidSpec("22203F", Header.POWERTRAIN_7E0, 24, 4), // engine torque
            PidSpec("22119F", Header.POWERTRAIN_7E0, 240, 180), // engine oil life
            PidSpec("22119F01", Header.POWERTRAIN_7E0, 240, 192), // engine oil life, GM selector variant
            PidSpec("221154", Header.POWERTRAIN_7E0, 48, 42), // engine oil temp, Volt community PID
            PidSpec("222883", Header.HV_PACK_7E1, 12, 2), // motor A current
            PidSpec("222885", Header.HV_PACK_7E1, 12, 2), // motor A voltage
            PidSpec("222884", Header.HV_PACK_7E1, 12, 4), // motor B current
            PidSpec("222886", Header.HV_PACK_7E1, 12, 4), // motor B voltage
            PidSpec("222889", Header.HV_PACK_7E1, 24, 10), // PRNDL / gear state
            PidSpec("222487", Header.HV_PACK_7E1, 48, 16, conditional = true), // EV distance this cycle
            PidSpec("221940", Header.TRANSMISSION_7E2, 48, 24), // trans temp
            PidSpec("22194001", Header.TRANSMISSION_7E2, 48, 34), // trans temp, GM selector variant
            // Charge-family PIDs only answer while plugged/charging on some Volt model-years, so they
            // are marked conditional = exempt from the negative-PID cache (a "NO DATA" while driving
            // must not disable them before a later charge session).
            PidSpec("22436B", Header.HV_PACK_7E4, 24, 14, conditional = true), // charger HV voltage
            PidSpec("22436C", Header.HV_PACK_7E4, 24, 14, conditional = true), // charger HV current
            PidSpec("224373", Header.HV_PACK_7E4, 24, 18, conditional = true), // charging mode
            PidSpec("224531", Header.HV_PACK_7E4, 24, 18, conditional = true), // charging level
            PidSpec("2243AF", Header.HV_PACK_7E4, 24, 22), // raw precise SOC
            // Cell-balance trio shares phase 22 with 2243AF so they batch on the same 7E4 header
            // switch; balance changes slowly, so every 24 cycles is plenty.
            PidSpec("224329", Header.HV_PACK_7E4, 24, 22), // minimum cell voltage
            PidSpec("22432B", Header.HV_PACK_7E4, 24, 22), // maximum cell voltage
            PidSpec("22435F", Header.HV_PACK_7E4, 24, 22), // cell SOC variation (balance proxy)
            PidSpec("2241B2", Header.HV_PACK_7E4, 48, 32), // battery coolant pump
            PidSpec("2241B4", Header.HV_PACK_7E4, 48, 32), // battery coolant valve
            // Min/max cell index identifies WHICH cell is the outlier; rarely moves, so poll it on
            // the slower 48-cycle lane alongside the coolant PIDs (same 7E4 phase 32).
            PidSpec("22432A", Header.HV_PACK_7E4, 48, 32), // minimum cell number
            PidSpec("22432C", Header.HV_PACK_7E4, 48, 32), // maximum cell number
            PidSpec("2241B6", Header.HV_PACK_7E4, 48, 38), // battery heater power
            PidSpec("22801E", Header.HV_PACK_7E4, 120, 96), // outside temp raw
            PidSpec("22801F", Header.HV_PACK_7E4, 120, 102), // outside temp filtered
            PidSpec("2243A5", Header.HV_PACK_7E4, 120, 84, conditional = true), // charge count
            PidSpec("22437D", Header.HV_PACK_7E4, 120, 90, conditional = true), // last charge energy
            PidSpec("2241A3", Header.HV_PACK_7E4, 240, 210), // HV battery capacity, rare trend sample
        )

    /** True if [spec] is due on the given (zero-based) cycle number. */
    @JvmStatic
    fun shouldPoll(
        cycleNum: Int,
        spec: PidSpec,
    ): Boolean {
        if (cycleNum < 0) {
            return false
        }
        return cycleNum % spec.periodCycles == spec.phaseOffset
    }

    /**
     * Returns the subset of [SPECS] that should be polled on this cycle. Preserves the declaration
     * order from [SPECS], which keeps reads of the same header adjacent so the engine can group them
     * under a single ATSH switch.
     */
    @JvmStatic
    fun dueOnCycle(cycleNum: Int): List<PidSpec> {
        if (cycleNum < 0) {
            return emptyList()
        }
        return SPECS.filter { shouldPoll(cycleNum, it) }
    }

    /**
     * True if [command] is a [PidSpec.conditional] PID — one the negative-PID cache must never
     * disable because it only answers in a specific vehicle mode. Unknown commands are not
     * conditional (the cache treats them normally).
     */
    @JvmStatic
    fun isConditional(command: String): Boolean = SPECS.firstOrNull { it.command == command }?.conditional ?: false
}
