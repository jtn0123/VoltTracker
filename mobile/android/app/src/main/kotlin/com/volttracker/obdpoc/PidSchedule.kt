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
        /**
         * ELM command that points the CAN receive filter at this node's reply ID, or `null` when the
         * adapter's automatic 7E8-7EF filter already accepts it. Modules outside the 7E0-7E7 range
         * reply on request+0x400 (0x257 answers on 0x657), which the auto filter drops.
         */
        @JvmField val receiveFilterCommand: String? = null,
    ) {
        BROADCAST(null),
        POWERTRAIN_7E0("ATSH7E0"),
        HV_PACK_7E1("ATSH7E1"),
        TRANSMISSION_7E2("ATSH7E2"),
        HV_PACK_7E4("ATSH7E4"),
        BRAKE_7E6("ATSH7E6"),
        CELL_BECM_7E7("ATSH7E7"),

        // Drive-unit motor-generator nodes, as polled by the OVMS Volt/Ampera module on a MY2017.
        // Keep these last: the engine restores the automatic receive filter once they are done.
        MOTOR_GEN_A_257("ATSH257", "ATCRA657"),
        MOTOR_GEN_B_258("ATSH258", "ATCRA658"),
    }

    /** Restore the broadcast header after a non-broadcast block of reads. */
    const val RESTORE_BROADCAST_HEADER_COMMAND = "ATSH7DF"

    /** Hand the CAN receive filter back to the adapter after a [Header.receiveFilterCommand]. */
    const val RESTORE_AUTO_RECEIVE_COMMAND = "ATAR"

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
     * The first live sample should prove the dashboard is alive as quickly as possible. Slow/deep
     * lanes still phase in on normal cycles after that first broadcast.
     */
    @JvmField
    val FIRST_SAMPLE_COMMANDS: List<String> = listOf("010D", "010C", "0149", "222414")

    private val firstSampleCommandSet: Set<String> = FIRST_SAMPLE_COMMANDS.toSet()

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
            // Fuel level heads the Drive range card: read it on the second cycle, then every ~20 s.
            PidSpec("012F", Header.BROADCAST, 12, 1), // fuel level
            // GM odometer (4 bytes, km * 64): the command the open-source Voltage app reads on
            // the Volt. 01A6 stays as the fallback; whichever the car refuses gets retired.
            PidSpec("2234B2", Header.BROADCAST, 240, 150), // odometer, GM
            PidSpec("01A6", Header.BROADCAST, 240, 186), // odometer, if supported
            // 22203F (engine torque) dropped: this gen-2 Volt answers with a single byte the 2-byte
            // voltWordValue decoder can't read, so engineTorqueNm was permanently null. The real
            // 1-byte scale is unproven (community (256A+B)/4 is 2-byte/gen-1), so rather than poll a
            // deep-lane PID we can't decode, it's removed until a Torque Pro cross-check on this car
            // pins the scale. See docs/obd-log-findings-2026-06-16.md (L3).
            PidSpec("22119F", Header.POWERTRAIN_7E0, 240, 180), // engine oil life
            PidSpec("22119F01", Header.POWERTRAIN_7E0, 240, 192), // engine oil life, GM selector variant
            PidSpec("221154", Header.POWERTRAIN_7E0, 48, 42), // engine oil temp, Volt community PID
            PidSpec("222883", Header.HV_PACK_7E1, 12, 2), // motor A current
            PidSpec("222885", Header.HV_PACK_7E1, 12, 2), // motor A voltage
            PidSpec("222884", Header.HV_PACK_7E1, 12, 4), // motor B current
            PidSpec("222886", Header.HV_PACK_7E1, 12, 4), // motor B voltage
            PidSpec("222889", Header.HV_PACK_7E1, 24, 10), // PRNDL / gear state
            // Readings Voltage shows but this app never asked for. Not yet confirmed on the target
            // car; the negative-PID cache retires any the car refuses, and each decoder is bounded.
            PidSpec("221C26", Header.HV_PACK_7E1, 24, 10), // inverter temperature
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
            PidSpec("224368", Header.HV_PACK_7E4, 24, 18, conditional = true), // charger AC voltage
            PidSpec("224369", Header.HV_PACK_7E4, 24, 18, conditional = true), // charger AC current
            PidSpec("2243AF", Header.HV_PACK_7E4, 24, 22), // raw precise SOC
            // The dash SOC and EV range are the Drive screen's headline. On the 24-cycle lane the
            // first read landed ~100 s into a session, so the ring showed the raw pack % (14) and
            // then jumped to the dash's (0). Every 6 cycles, sharing 22434F's 7E4 phase: ~10 s.
            PidSpec("228334", Header.HV_PACK_7E4, 6, 5), // dash-displayed SOC (read by Voltage)
            PidSpec("2241A6", Header.HV_PACK_7E4, 6, 5), // car's own EV range estimate (OVMS)
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
            PidSpec("221C43", Header.HV_PACK_7E4, 48, 32), // PEM / charger coolant loop temp (OVMS)
            PidSpec("2241B6", Header.HV_PACK_7E4, 48, 38), // battery heater power
            PidSpec("22439E", Header.HV_PACK_7E4, 48, 38), // battery heater duty (OVMS)
            PidSpec("22801E", Header.HV_PACK_7E4, 120, 96), // outside temp raw
            // The Car tab's climate line: first read on 22434F's early 7E4 switch, not ~3 min in.
            PidSpec("22801F", Header.HV_PACK_7E4, 24, 5), // outside temp filtered
            PidSpec("2243A5", Header.HV_PACK_7E4, 120, 84, conditional = true), // charge count
            PidSpec("22437D", Header.HV_PACK_7E4, 120, 90, conditional = true), // last charge energy
            PidSpec("2241A3", Header.HV_PACK_7E4, 240, 210), // HV battery capacity, rare trend sample
            PidSpec("2240E9", Header.HV_PACK_7E4, 240, 210), // pack internal resistance (read by Voltage)
            PidSpec("2243A6", Header.HV_PACK_7E4, 240, 210), // HV isolation resistance (read by Voltage)
            PidSpec("224389", Header.HV_PACK_7E4, 240, 210), // lifetime charge energy (OVMS)
            // Six pack-section temperatures from the BECM cell interface. Thermal mass makes them
            // slow, and six reads is a heavy block, so they share one rare 7E7 switch.
            PidSpec("2240D7", Header.CELL_BECM_7E7, 240, 60), // pack section 1 temperature
            PidSpec("2240D9", Header.CELL_BECM_7E7, 240, 60), // pack section 2 temperature
            PidSpec("2240DB", Header.CELL_BECM_7E7, 240, 60), // pack section 3 temperature
            PidSpec("2240DD", Header.CELL_BECM_7E7, 240, 60), // pack section 4 temperature
            PidSpec("2240DF", Header.CELL_BECM_7E7, 240, 60), // pack section 5 temperature
            PidSpec("2240E1", Header.CELL_BECM_7E7, 240, 60), // pack section 6 temperature
            // Motor-generator temperatures live on their own drive-unit nodes (0x257 / 0x258, replies
            // on 0x657 / 0x658), not 7E1: that is where the OVMS Volt module reads them on a MY2017.
            // Every ~10 s like OVMS; both share one phase so the filter restore happens once.
            PidSpec("2228CB", Header.MOTOR_GEN_A_257, 48, 44), // motor-generator A temperature
            PidSpec("22368F", Header.MOTOR_GEN_B_258, 48, 44), // motor-generator B temperature
        )

    private val specsByCommand: Map<String, PidSpec> = SPECS.associateBy { it.command }
    private val mode01BatchCommandSet: Set<String> = MODE_01_BATCH_COMMANDS.toSet()

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

    @JvmStatic
    fun firstSampleSpecs(): List<PidSpec> = SPECS.filter { firstSampleCommandSet.contains(it.command) }

    @JvmStatic
    fun specFor(command: String): PidSpec? = specsByCommand[command]

    @JvmStatic
    fun isMode01BatchCommand(command: String): Boolean = mode01BatchCommandSet.contains(command)

    /**
     * True if [command] is a [PidSpec.conditional] PID — one the negative-PID cache must never
     * disable because it only answers in a specific vehicle mode. Unknown commands are not
     * conditional (the cache treats them normally).
     */
    @JvmStatic
    fun isConditional(command: String): Boolean = specFor(command)?.conditional ?: false
}
