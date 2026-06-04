package com.volttracker.obdpoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiered/staggered polling schedule for the OBD live-data loop.
 *
 * <p>The Volt exposes values that change at very different rates — speed and RPM update many times
 * per second while in motion, while coolant temperature, battery temperature, and adapter voltage
 * barely move over tens of seconds. Polling every PID every cycle wastes adapter bandwidth and,
 * more visibly, slows down the values that <em>do</em> matter — speed and RPM end up bound by the
 * time it takes to ALSO read coolant temp, battery temp, etc.
 *
 * <p>This schedule splits PIDs into four lanes by how fast each value actually changes, then
 * staggers the slower lanes across consecutive cycles so the per-cycle cost stays roughly flat
 * instead of having "heavy" cycles every Nth iteration:
 *
 * <pre>
 *   Hot lane (period 1)    — speed, RPM, pedal, pack current      polled every cycle
 *   Warm lane (period 2)   — load, ICE throttle, pack voltage     polled every other cycle
 *   Slow lane (period 6)   — ATRV adapter voltage, SOC            polled every ~6 cycles
 *   Thermal lane (period 12) — coolant temp, HV battery temp      polled every ~12 cycles
 *   Deep lane (period 24+) — fuel, charger state, charge count    polled rarely
 * </pre>
 *
 * <p>Pack power is intentionally asymmetric: current changes quickly and stays hot, while pack
 * voltage moves slowly enough to reuse for one cycle. That keeps the kW display responsive without
 * paying the voltage PID round-trip every loop. Phase offsets are chosen to avoid stacking multiple
 * slow same-lane PIDs on the same cycle; the rare {@code ATSH 7E4} header switch is isolated to the
 * battery-temperature phase because {@code 22434F} is the only PID on that header.
 *
 * <p>Carry-forward semantics: when a PID is not polled on a given cycle, the polling engine reuses
 * its last-known raw response, so every sample published to the dashboard still contains every key
 * (no flicker). A {@code *StaleMs} companion field on the sample lets the dashboard surface "this
 * value is N seconds old" if it wants to.
 *
 * <p>This class is pure data + arithmetic so it can be unit-tested on the JVM without any adapter
 * or Robolectric. {@link ObdPollingEngine} owns the actual I/O and consults {@link
 * #dueOnCycle(int)} once per cycle.
 */
final class PidSchedule {

    private PidSchedule() {}

    /** ELM header the PID lives behind. Only the non-broadcast headers require an ATSH switch. */
    enum Header {
        BROADCAST(null),
        POWERTRAIN_7E0("ATSH7E0"),
        HV_PACK_7E1("ATSH7E1"),
        TRANSMISSION_7E2("ATSH7E2"),
        HV_PACK_7E4("ATSH7E4");

        /** ELM command that selects this header, or {@code null} for the default 7DF broadcast. */
        final String atCommand;

        Header(String atCommand) {
            this.atCommand = atCommand;
        }
    }

    /** Restore the broadcast header after a non-broadcast block of reads. */
    static final String RESTORE_BROADCAST_HEADER_COMMAND = "ATSH7DF";

    /**
     * Hot-lane broadcast Mode-01 PIDs that get batched into a single request when the adapter
     * supports it. Listing both the full command strings (so the engine can match against {@link
     * PidSpec#command}) and the bare PID hex (so {@link ObdProtocol#buildMode01MultiCommand} can
     * assemble the batched request) avoids substring slicing in two places.
     *
     * <p>Order matters: the ELM327 reply includes frames in the same order the PIDs were requested,
     * but each parser searches for its own {@code "41XX"} marker so the order is only a wire-format
     * detail — the dashboard never sees it.
     */
    static final List<String> MODE_01_BATCH_COMMANDS =
            Collections.unmodifiableList(new ArrayList<>(List.of("010D", "010C", "0149")));

    static final List<String> MODE_01_BATCH_PIDS_HEX =
            Collections.unmodifiableList(new ArrayList<>(List.of("0D", "0C", "49")));

    /**
     * Polling spec for a single PID. {@code periodCycles == 1} means "every cycle"; higher values
     * stagger this PID across cycles. {@code phaseOffset} picks which slot inside that period this
     * PID lands on, so two slow PIDs of the same period can be spread out instead of stacking on
     * the same cycle.
     */
    static final class PidSpec {
        final String command;
        final Header header;
        final int periodCycles;
        final int phaseOffset;

        PidSpec(String command, Header header, int periodCycles, int phaseOffset) {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("command must be non-empty");
            }
            if (header == null) {
                throw new IllegalArgumentException("header must be non-null");
            }
            if (periodCycles < 1) {
                throw new IllegalArgumentException("periodCycles must be >= 1");
            }
            if (phaseOffset < 0 || phaseOffset >= periodCycles) {
                throw new IllegalArgumentException(
                        "phaseOffset must satisfy 0 <= phaseOffset < periodCycles, got "
                                + phaseOffset
                                + " for period "
                                + periodCycles);
            }
            this.command = command;
            this.header = header;
            this.periodCycles = periodCycles;
            this.phaseOffset = phaseOffset;
        }
    }

    // Full schedule. Keep PIDs of the same header grouped here so the diff between this list
    // and runtime header-grouping in ObdPollingEngine stays obvious.
    static final List<PidSpec> SPECS;

    static {
        List<PidSpec> specs = new ArrayList<>();
        // --- Hot lane (every cycle) -- drive-critical, must stay snappy ------------------
        specs.add(new PidSpec("010D", Header.BROADCAST, 1, 0)); // vehicle speed
        specs.add(new PidSpec("010C", Header.BROADCAST, 1, 0)); // engine RPM
        // 0149 is the drive-by-wire accelerator pedal — the Volt returns a constant for 0111
        // because that PID is the ICE throttle body angle, not the pedal. We poll both and
        // prefer 0149 in the dashboard rendering when it's responding.
        specs.add(new PidSpec("0149", Header.BROADCAST, 1, 0)); // accelerator pedal position D
        specs.add(new PidSpec("222414", Header.HV_PACK_7E1, 1, 0)); // HV pack current

        // --- Warm lane (every 2 cycles) -- useful live context, but not speedometer-fast --
        specs.add(new PidSpec("0104", Header.BROADCAST, 2, 0)); // engine load
        specs.add(
                new PidSpec(
                        "0111", Header.BROADCAST, 2, 1)); // throttle position (ICE throttle body)
        specs.add(new PidSpec("222429", Header.HV_PACK_7E1, 2, 0)); // HV pack voltage

        // --- Slow lane (every 6 cycles) -- changes slowly during normal driving ----------
        specs.add(new PidSpec("015B", Header.BROADCAST, 6, 0)); // state of charge
        specs.add(new PidSpec("ATRV", Header.BROADCAST, 6, 3)); // adapter voltage

        // --- Thermal lane (every 12 cycles) -- thermal mass, very slow change ------------
        specs.add(new PidSpec("0105", Header.BROADCAST, 12, 8)); // coolant temp
        specs.add(new PidSpec("22434F", Header.HV_PACK_7E4, 12, 5)); // HV battery temp
        specs.add(new PidSpec("010F", Header.BROADCAST, 24, 20)); // intake air temp

        // --- Deep lane (every 24+ cycles) -- scan-validated context, not dashboard-fast ---
        specs.add(new PidSpec("0142", Header.BROADCAST, 24, 6)); // control module voltage
        specs.add(new PidSpec("011F", Header.BROADCAST, 24, 12)); // engine run time
        specs.add(new PidSpec("015C", Header.BROADCAST, 48, 36)); // engine oil temp
        specs.add(new PidSpec("012F", Header.BROADCAST, 48, 30)); // fuel level
        specs.add(new PidSpec("01A6", Header.BROADCAST, 240, 186)); // odometer, if supported
        specs.add(new PidSpec("22203F", Header.POWERTRAIN_7E0, 24, 4)); // engine torque
        specs.add(new PidSpec("22119F", Header.POWERTRAIN_7E0, 240, 180)); // engine oil life
        specs.add(
                new PidSpec(
                        "22119F01",
                        Header.POWERTRAIN_7E0,
                        240,
                        192)); // engine oil life, GM selector variant
        specs.add(
                new PidSpec(
                        "221154",
                        Header.POWERTRAIN_7E0,
                        48,
                        42)); // engine oil temp, Volt community PID
        specs.add(new PidSpec("222883", Header.HV_PACK_7E1, 12, 2)); // motor A current
        specs.add(new PidSpec("222885", Header.HV_PACK_7E1, 12, 2)); // motor A voltage
        specs.add(new PidSpec("222884", Header.HV_PACK_7E1, 12, 4)); // motor B current
        specs.add(new PidSpec("222886", Header.HV_PACK_7E1, 12, 4)); // motor B voltage
        specs.add(new PidSpec("222889", Header.HV_PACK_7E1, 24, 10)); // PRNDL / gear state
        specs.add(new PidSpec("222487", Header.HV_PACK_7E1, 48, 16)); // EV distance this cycle
        specs.add(new PidSpec("221940", Header.TRANSMISSION_7E2, 48, 24)); // trans temp
        specs.add(
                new PidSpec(
                        "22194001",
                        Header.TRANSMISSION_7E2,
                        48,
                        34)); // trans temp, GM selector variant
        specs.add(new PidSpec("22436B", Header.HV_PACK_7E4, 24, 14)); // charger HV voltage
        specs.add(new PidSpec("22436C", Header.HV_PACK_7E4, 24, 14)); // charger HV current
        specs.add(new PidSpec("224373", Header.HV_PACK_7E4, 24, 18)); // charging mode
        specs.add(new PidSpec("224531", Header.HV_PACK_7E4, 24, 18)); // charging level
        specs.add(new PidSpec("2243AF", Header.HV_PACK_7E4, 24, 22)); // raw precise SOC
        specs.add(new PidSpec("2241B2", Header.HV_PACK_7E4, 48, 32)); // battery coolant pump
        specs.add(new PidSpec("2241B4", Header.HV_PACK_7E4, 48, 32)); // battery coolant valve
        specs.add(new PidSpec("2241B6", Header.HV_PACK_7E4, 48, 38)); // battery heater power
        specs.add(new PidSpec("22801E", Header.HV_PACK_7E4, 120, 96)); // outside temp raw
        specs.add(new PidSpec("22801F", Header.HV_PACK_7E4, 120, 102)); // outside temp filtered
        specs.add(new PidSpec("2243A5", Header.HV_PACK_7E4, 120, 84)); // charge count
        specs.add(new PidSpec("22437D", Header.HV_PACK_7E4, 120, 90)); // last charge energy

        SPECS = Collections.unmodifiableList(specs);
    }

    /** True if {@code spec} is due on the given (zero-based) cycle number. */
    static boolean shouldPoll(int cycleNum, PidSpec spec) {
        if (cycleNum < 0) {
            return false;
        }
        return (cycleNum % spec.periodCycles) == spec.phaseOffset;
    }

    /**
     * Returns the subset of {@link #SPECS} that should be polled on this cycle. Preserves the
     * declaration order from {@link #SPECS}, which keeps reads of the same header adjacent so the
     * engine can group them under a single ATSH switch.
     */
    static List<PidSpec> dueOnCycle(int cycleNum) {
        if (cycleNum < 0) {
            return Collections.emptyList();
        }
        List<PidSpec> due = new ArrayList<>();
        for (PidSpec spec : SPECS) {
            if (shouldPoll(cycleNum, spec)) {
                due.add(spec);
            }
        }
        return due;
    }
}
