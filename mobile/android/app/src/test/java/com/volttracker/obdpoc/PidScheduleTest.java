package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.volttracker.obdpoc.PidSchedule.Header;
import com.volttracker.obdpoc.PidSchedule.PidSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit coverage for the tiered/staggered polling schedule (B6).
 *
 * <p>The pure-arithmetic schedule is easy to drift accidentally — a phase offset typo would
 * silently re-stack two slow PIDs on the same cycle and reintroduce the spiky cycle we're trying to
 * avoid. These tests pin the expected cadence and stagger explicitly so any future edit to {@link
 * PidSchedule#SPECS} fails loudly if it changes the contract.
 */
public class PidScheduleTest {

    @Test
    public void specsAreInternallyConsistent() {
        assertFalse("at least one spec expected", PidSchedule.SPECS.isEmpty());
        for (PidSpec spec : PidSchedule.SPECS) {
            assertNotNull("command must be set", spec.command);
            assertNotNull("header must be set", spec.header);
            assertTrue("periodCycles >= 1", spec.periodCycles >= 1);
            assertTrue("phaseOffset >= 0", spec.phaseOffset >= 0);
            assertTrue("phaseOffset < periodCycles", spec.phaseOffset < spec.periodCycles);
        }
    }

    @Test
    public void tier1PidsArePolledEveryCycle() {
        // Drive-critical PIDs that must update every cycle.
        String[] tier1 = {"010D", "010C", "0104", "0111", "222429", "222414"};
        for (String command : tier1) {
            PidSpec spec = findByCommand(command);
            assertEquals("Tier 1 " + command + " must have period 1", 1, spec.periodCycles);
            assertEquals("Tier 1 " + command + " phase must be 0", 0, spec.phaseOffset);
        }
    }

    @Test
    public void tier2PidsAreStaggered() {
        PidSpec atrv = findByCommand("ATRV");
        PidSpec soc = findByCommand("015B");
        assertEquals("ATRV period", 4, atrv.periodCycles);
        assertEquals("SOC period", 4, soc.periodCycles);
        assertTrue("Tier 2 phases must differ", atrv.phaseOffset != soc.phaseOffset);
    }

    @Test
    public void tier3PidsAreStaggered() {
        PidSpec coolant = findByCommand("0105");
        PidSpec batteryTemp = findByCommand("22434F");
        assertEquals("coolant period", 10, coolant.periodCycles);
        assertEquals("batteryTemp period", 10, batteryTemp.periodCycles);
        assertTrue("Tier 3 phases must differ", coolant.phaseOffset != batteryTemp.phaseOffset);
    }

    /**
     * Run 40 cycles and assert the exact send count per PID matches the declared cadence. This is
     * the schedule's contract — if the contract changes, this fails loudly.
     */
    @Test
    public void over40CyclesEachPidHitsItsDeclaredRate() {
        Map<String, Integer> sendCount = new HashMap<>();
        for (int cycle = 0; cycle < 40; cycle++) {
            for (PidSpec spec : PidSchedule.dueOnCycle(cycle)) {
                sendCount.merge(spec.command, 1, Integer::sum);
            }
        }
        // Tier 1 (period 1) — exactly 40 sends in 40 cycles
        for (String tier1 : new String[] {"010D", "010C", "0104", "0111", "222429", "222414"}) {
            assertEquals(
                    tier1 + " (Tier 1) should fire on every cycle",
                    Integer.valueOf(40),
                    sendCount.get(tier1));
        }
        // Tier 2 (period 4) — 40/4 = 10 sends
        assertEquals("ATRV (Tier 2) every 4 cycles", Integer.valueOf(10), sendCount.get("ATRV"));
        assertEquals("SOC (Tier 2) every 4 cycles", Integer.valueOf(10), sendCount.get("015B"));
        // Tier 3 (period 10) — 40/10 = 4 sends
        assertEquals("coolant (Tier 3) every 10 cycles", Integer.valueOf(4), sendCount.get("0105"));
        assertEquals(
                "batteryTemp (Tier 3) every 10 cycles",
                Integer.valueOf(4),
                sendCount.get("22434F"));
    }

    /**
     * The cycle count should stay roughly flat — no single cycle should poll BOTH Tier 2 PIDs at
     * once, or BOTH Tier 3 PIDs at once. (Mixing one of each tier is fine and expected on cycles 0,
     * 10, 20, etc. — that's the maximum-cost cycle by design.)
     */
    @Test
    public void noTwoSameTierPidsLandOnTheSameCycle() {
        for (int cycle = 0; cycle < 40; cycle++) {
            List<PidSpec> due = PidSchedule.dueOnCycle(cycle);
            int tier2Count = countByPeriod(due, 4);
            int tier3Count = countByPeriod(due, 10);
            assertTrue("cycle " + cycle + " has >1 Tier 2 PID, breaking stagger", tier2Count <= 1);
            assertTrue("cycle " + cycle + " has >1 Tier 3 PID, breaking stagger", tier3Count <= 1);
        }
    }

    @Test
    public void cycleZeroPollsAllPhaseZeroSpecs() {
        List<PidSpec> due = PidSchedule.dueOnCycle(0);
        // Every spec with phaseOffset == 0 should fire on cycle 0.
        for (PidSpec spec : PidSchedule.SPECS) {
            if (spec.phaseOffset == 0) {
                assertTrue(
                        spec.command + " should be due on cycle 0",
                        due.stream().anyMatch(s -> s.command.equals(spec.command)));
            }
        }
    }

    @Test
    public void negativeCycleNumberReturnsEmpty() {
        assertTrue(PidSchedule.dueOnCycle(-1).isEmpty());
        assertTrue(PidSchedule.dueOnCycle(-100).isEmpty());
    }

    @Test
    public void invalidSpecConstructionThrows() {
        try {
            new PidSpec(null, Header.BROADCAST, 1, 0);
            fail("null command must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PidSpec("010D", Header.BROADCAST, 0, 0);
            fail("period < 1 must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PidSpec("010D", Header.BROADCAST, 4, 4);
            fail("phaseOffset >= period must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PidSpec("010D", Header.BROADCAST, 4, -1);
            fail("negative phaseOffset must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static PidSpec findByCommand(String command) {
        for (PidSpec spec : PidSchedule.SPECS) {
            if (spec.command.equals(command)) {
                return spec;
            }
        }
        throw new AssertionError("no spec for command " + command);
    }

    private static int countByPeriod(List<PidSpec> specs, int period) {
        int count = 0;
        for (PidSpec spec : specs) {
            if (spec.periodCycles == period) {
                count += 1;
            }
        }
        return count;
    }

    // ---- B7 Mode-01 multi-PID batch constants ---------------------------------------

    @org.junit.Test
    public void mode01BatchCommands_matchEveryPidHexInTheSameOrder() {
        // The two parallel lists must stay in lockstep: same length, and the PID hex must
        // be the last 2 chars of each batch command. A future addition that breaks this
        // would corrupt the batched read.
        assertEquals(
                PidSchedule.MODE_01_BATCH_COMMANDS.size(),
                PidSchedule.MODE_01_BATCH_PIDS_HEX.size());
        for (int i = 0; i < PidSchedule.MODE_01_BATCH_COMMANDS.size(); i++) {
            String command = PidSchedule.MODE_01_BATCH_COMMANDS.get(i);
            String pidHex = PidSchedule.MODE_01_BATCH_PIDS_HEX.get(i);
            assertEquals(
                    "batch command " + command + " must end in PID hex " + pidHex,
                    pidHex,
                    command.substring(2));
            assertEquals("01", command.substring(0, 2));
        }
    }

    @org.junit.Test
    public void mode01BatchCommands_areAllTier1BroadcastSpecs() {
        // Batched PIDs must all be period=1, broadcast header — otherwise the engine's
        // "all batch commands are due" predicate would never fire and the optimization
        // wouldn't engage.
        for (String command : PidSchedule.MODE_01_BATCH_COMMANDS) {
            PidSchedule.PidSpec spec = null;
            for (PidSchedule.PidSpec candidate : PidSchedule.SPECS) {
                if (command.equals(candidate.command)) {
                    spec = candidate;
                    break;
                }
            }
            assertNotNull("batch command " + command + " missing from SPECS", spec);
            assertEquals("batch command " + command + " must be period=1", 1, spec.periodCycles);
            assertEquals(
                    "batch command " + command + " must be broadcast header",
                    PidSchedule.Header.BROADCAST,
                    spec.header);
        }
    }
}
