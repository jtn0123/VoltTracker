package com.volttracker.obdpoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiered/staggered polling schedule for the OBD live-data loop (B6).
 *
 * <p>The Volt exposes values that change at very different rates — speed and RPM update many times
 * per second while in motion, while coolant temperature, battery temperature, and adapter voltage
 * barely move over tens of seconds. Polling every PID every cycle wastes adapter bandwidth and,
 * more visibly, slows down the values that <em>do</em> matter — speed and RPM end up bound by the
 * time it takes to ALSO read coolant temp, battery temp, etc.
 *
 * <p>This schedule splits PIDs into three tiers by how fast each value actually changes, then
 * staggers the slow tiers across consecutive cycles so the per-cycle cost stays roughly flat
 * instead of having "heavy" cycles every Nth iteration:
 *
 * <pre>
 *   Tier 1 (period 1)  — speed, RPM, throttle, load, pedal, pack V, pack I polled every cycle
 *   Tier 2 (period 4)  — ATRV adapter voltage, SOC                         polled every ~4 cycles
 *   Tier 3 (period 10) — coolant temp, HV battery temp                     polled every ~10 cycles
 * </pre>
 *
 * <p>Phase offsets are chosen so each cycle adds at most one Tier 2 PID and at most one Tier 3 PID
 * — never both of the same tier on the same cycle. The "rare heavy" cycle (5) is the one where
 * battery temp is due: that's the only cycle that pays the {@code ATSH 7E4} header switch overhead,
 * because {@code 22434F} (battery temp) is the only PID on that header.
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
        HV_PACK_7E1("ATSH7E1"),
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
     * B7: Tier-1 broadcast Mode-01 PIDs that get batched into a single request when the adapter
     * supports it. Listing both the full command strings (so the engine can match against {@link
     * PidSpec#command}) and the bare PID hex (so {@link ObdProtocol#buildMode01MultiCommand} can
     * assemble the batched request) avoids substring slicing in two places.
     *
     * <p>Order matters: the ELM327 reply includes frames in the same order the PIDs were requested,
     * but each parser searches for its own {@code "41XX"} marker so the order is only a wire-format
     * detail — the dashboard never sees it.
     */
    static final List<String> MODE_01_BATCH_COMMANDS =
            Collections.unmodifiableList(
                    new ArrayList<>(List.of("010D", "010C", "0104", "0111", "0149")));

    static final List<String> MODE_01_BATCH_PIDS_HEX =
            Collections.unmodifiableList(new ArrayList<>(List.of("0D", "0C", "04", "11", "49")));

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
        // --- Tier 1 (every cycle) -- drive-critical, must stay snappy --------------------
        specs.add(new PidSpec("010D", Header.BROADCAST, 1, 0)); // vehicle speed
        specs.add(new PidSpec("010C", Header.BROADCAST, 1, 0)); // engine RPM
        specs.add(new PidSpec("0104", Header.BROADCAST, 1, 0)); // engine load
        specs.add(
                new PidSpec(
                        "0111", Header.BROADCAST, 1, 0)); // throttle position (ICE throttle body)
        // 0149 is the drive-by-wire accelerator pedal — the Volt returns a constant for 0111
        // because that PID is the ICE throttle body angle, not the pedal. We poll both and
        // prefer 0149 in the dashboard rendering when it's responding.
        specs.add(new PidSpec("0149", Header.BROADCAST, 1, 0)); // accelerator pedal position D
        specs.add(new PidSpec("222429", Header.HV_PACK_7E1, 1, 0)); // HV pack voltage
        specs.add(new PidSpec("222414", Header.HV_PACK_7E1, 1, 0)); // HV pack current

        // --- Tier 2 (every 4 cycles ≈ 7s) -- changes slowly during normal driving --------
        specs.add(new PidSpec("ATRV", Header.BROADCAST, 4, 0)); // adapter voltage
        specs.add(new PidSpec("015B", Header.BROADCAST, 4, 2)); // state of charge

        // --- Tier 3 (every 10 cycles ≈ 17s) -- thermal mass, very slow change ------------
        specs.add(new PidSpec("0105", Header.BROADCAST, 10, 0)); // coolant temp
        specs.add(new PidSpec("22434F", Header.HV_PACK_7E4, 10, 5)); // HV battery temp

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
