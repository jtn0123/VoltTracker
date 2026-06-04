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
    public void hotLanePidsArePolledEveryCycle() {
        // Drive-critical PIDs that must update every cycle.
        String[] hotLane = {"010D", "010C", "0149", "222414"};
        for (String command : hotLane) {
            PidSpec spec = findByCommand(command);
            assertEquals("Hot lane " + command + " must have period 1", 1, spec.periodCycles);
            assertEquals("Hot lane " + command + " phase must be 0", 0, spec.phaseOffset);
        }
    }

    @Test
    public void warmLanePidsUseEveryOtherCyclePhases() {
        PidSpec load = findByCommand("0104");
        PidSpec throttle = findByCommand("0111");
        PidSpec packVoltage = findByCommand("222429");
        assertEquals("load period", 2, load.periodCycles);
        assertEquals("ICE throttle period", 2, throttle.periodCycles);
        assertEquals("pack voltage period", 2, packVoltage.periodCycles);
        assertTrue(
                "load and ICE throttle phases must differ",
                load.phaseOffset != throttle.phaseOffset);
        assertEquals(
                "pack voltage shares the load phase", load.phaseOffset, packVoltage.phaseOffset);
    }

    @Test
    public void slowAndThermalPidsAreStaggered() {
        PidSpec atrv = findByCommand("ATRV");
        PidSpec soc = findByCommand("015B");
        PidSpec coolant = findByCommand("0105");
        PidSpec batteryTemp = findByCommand("22434F");
        assertEquals("ATRV period", 6, atrv.periodCycles);
        assertEquals("SOC period", 6, soc.periodCycles);
        assertTrue("slow-lane phases must differ", atrv.phaseOffset != soc.phaseOffset);
        assertEquals("coolant period", 12, coolant.periodCycles);
        assertEquals("batteryTemp period", 12, batteryTemp.periodCycles);
        assertTrue(
                "thermal-lane phases must differ", coolant.phaseOffset != batteryTemp.phaseOffset);
    }

    @Test
    public void deepLaneKeepsScanValidatedContextOffTheFastPath() {
        for (String command :
                new String[] {
                    "0142",
                    "011F",
                    "012F",
                    "22436B",
                    "22436C",
                    "224373",
                    "224531",
                    "2243AF",
                    "015C",
                    "01A6",
                    "22203F",
                    "22119F",
                    "22119F01",
                    "221154",
                    "222889",
                    "222487",
                    "221940",
                    "22194001",
                    "2241B2",
                    "2241B4",
                    "2241B6",
                    "22801E",
                    "22801F",
                    "2243A5",
                    "22437D"
                }) {
            PidSpec spec = findByCommand(command);
            assertTrue(
                    "deep-lane command " + command + " must not poll faster than every 24 cycles",
                    spec.periodCycles >= 24);
        }
        assertEquals(120, findByCommand("2243A5").periodCycles);
        assertEquals(120, findByCommand("22437D").periodCycles);
        assertEquals(240, findByCommand("22119F").periodCycles);
        assertEquals(240, findByCommand("22119F01").periodCycles);
        assertEquals(240, findByCommand("01A6").periodCycles);
    }

    @Test
    public void motorDetailStaysOffTheHotPath() {
        for (String command : new String[] {"222883", "222884", "222885", "222886"}) {
            PidSpec spec = findByCommand(command);
            assertEquals("motor detail cadence " + command, 12, spec.periodCycles);
        }
    }

    /**
     * Run 48 cycles and assert the exact send count per PID matches the declared cadence. This is
     * the schedule's contract — if the contract changes, this fails loudly.
     */
    @Test
    public void over48CyclesEachPidHitsItsDeclaredRate() {
        Map<String, Integer> sendCount = new HashMap<>();
        for (int cycle = 0; cycle < 48; cycle++) {
            for (PidSpec spec : PidSchedule.dueOnCycle(cycle)) {
                sendCount.merge(spec.command, 1, Integer::sum);
            }
        }
        // Hot lane (period 1) — exactly 48 sends in 48 cycles
        for (String hotLane : new String[] {"010D", "010C", "0149", "222414"}) {
            assertEquals(
                    hotLane + " (hot lane) should fire on every cycle",
                    Integer.valueOf(48),
                    sendCount.get(hotLane));
        }
        // Warm lane (period 2) — 48/2 = 24 sends
        assertEquals("load every 2 cycles", Integer.valueOf(24), sendCount.get("0104"));
        assertEquals("ICE throttle every 2 cycles", Integer.valueOf(24), sendCount.get("0111"));
        assertEquals("pack voltage every 2 cycles", Integer.valueOf(24), sendCount.get("222429"));
        // Slow lane (period 6) — 48/6 = 8 sends
        assertEquals("ATRV every 6 cycles", Integer.valueOf(8), sendCount.get("ATRV"));
        assertEquals("SOC every 6 cycles", Integer.valueOf(8), sendCount.get("015B"));
        // Thermal lane (period 12) — 48/12 = 4 sends
        assertEquals("coolant every 12 cycles", Integer.valueOf(4), sendCount.get("0105"));
        assertEquals("batteryTemp every 12 cycles", Integer.valueOf(4), sendCount.get("22434F"));
        assertEquals(
                "motor A current every 12 cycles", Integer.valueOf(4), sendCount.get("222883"));
        assertEquals(
                "motor A voltage every 12 cycles", Integer.valueOf(4), sendCount.get("222885"));
        assertEquals(
                "motor B current every 12 cycles", Integer.valueOf(4), sendCount.get("222884"));
        assertEquals(
                "motor B voltage every 12 cycles", Integer.valueOf(4), sendCount.get("222886"));
        // Deep lane — scan-validated context stays rare enough not to slow the hot loop.
        for (String deep24 :
                new String[] {
                    "0142", "011F", "010F", "22203F", "222889", "22436B", "22436C", "224373",
                    "224531", "2243AF"
                }) {
            assertEquals(deep24 + " every 24 cycles", Integer.valueOf(2), sendCount.get(deep24));
        }
        for (String deep48 :
                new String[] {
                    "012F",
                    "015C",
                    "221154",
                    "222487",
                    "221940",
                    "22194001",
                    "2241B2",
                    "2241B4",
                    "2241B6"
                }) {
            assertEquals(deep48 + " every 48 cycles", Integer.valueOf(1), sendCount.get(deep48));
        }
    }

    /**
     * The cycle count should stay roughly flat — no single cycle should poll both slow-lane PIDs or
     * both thermal-lane PIDs at once.
     */
    @Test
    public void noTwoSlowOrThermalLanePidsLandOnTheSameCycle() {
        for (int cycle = 0; cycle < 48; cycle++) {
            List<PidSpec> due = PidSchedule.dueOnCycle(cycle);
            int slowCount = countMatchingCommands(due, "ATRV", "015B");
            int thermalCount = countMatchingCommands(due, "0105", "22434F");
            assertTrue("cycle " + cycle + " has >1 slow-lane PID", slowCount <= 1);
            assertTrue("cycle " + cycle + " has >1 thermal-lane PID", thermalCount <= 1);
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

    // ---- Generalized Mode-01 same-cycle batching precondition -----------------------

    /**
     * The generalized Mode-01 batching (G2) groups any same-header Mode-01 PIDs that fall due on
     * the same cycle. This pins the schedule's precondition for that path: there exists a cycle on
     * which two non-hot broadcast Mode-01 PIDs (load 0104 + SOC 015B) coincide, so the engine has a
     * real multi-lane group to batch. Hot-lane batching is a separate, dedicated path.
     */
    @Test
    public void aMultiTierSameCycleMode01BatchGroupExists() {
        boolean foundCoincidence = false;
        for (int cycle = 0; cycle < 48 && !foundCoincidence; cycle++) {
            List<PidSpec> due = PidSchedule.dueOnCycle(cycle);
            List<String> broadcastMode01 = new java.util.ArrayList<>();
            for (PidSpec spec : due) {
                boolean hotLane = PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command);
                boolean broadcastMode01Cmd =
                        spec.header == Header.BROADCAST
                                && spec.command.length() == 4
                                && spec.command.startsWith("01");
                if (broadcastMode01Cmd && !hotLane) {
                    broadcastMode01.add(spec.command);
                }
            }
            if (broadcastMode01.size() >= 2) {
                foundCoincidence = true;
                assertTrue(
                        "expected load in the multi-lane group on cycle " + cycle,
                        broadcastMode01.contains("0104"));
                assertTrue(
                        "expected SOC in the multi-lane group on cycle " + cycle,
                        broadcastMode01.contains("015B"));
            }
        }
        assertTrue(
                "schedule must produce at least one multi-lane same-cycle Mode-01 batch group",
                foundCoincidence);
    }

    private static PidSpec findByCommand(String command) {
        for (PidSpec spec : PidSchedule.SPECS) {
            if (spec.command.equals(command)) {
                return spec;
            }
        }
        throw new AssertionError("no spec for command " + command);
    }

    private static int countMatchingCommands(List<PidSpec> specs, String... commands) {
        int count = 0;
        for (PidSpec spec : specs) {
            for (String command : commands) {
                if (spec.command.equals(command)) {
                    count += 1;
                    break;
                }
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
