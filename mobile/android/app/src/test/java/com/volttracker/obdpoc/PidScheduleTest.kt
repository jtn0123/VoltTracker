package com.volttracker.obdpoc

import com.volttracker.obdpoc.PidSchedule.Header
import com.volttracker.obdpoc.PidSchedule.PidSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the tiered/staggered polling schedule (B6).
 *
 * The pure-arithmetic schedule is easy to drift accidentally — a phase offset typo would silently
 * re-stack two slow PIDs on the same cycle and reintroduce the spiky cycle we're trying to avoid.
 * These tests pin the expected cadence and stagger explicitly so any future edit to
 * [PidSchedule.SPECS] fails loudly if it changes the contract.
 */
class PidScheduleTest {
    @Test
    fun specsAreInternallyConsistent() {
        assertFalse("at least one spec expected", PidSchedule.SPECS.isEmpty())
        for (spec in PidSchedule.SPECS) {
            assertNotNull("command must be set", spec.command)
            assertNotNull("header must be set", spec.header)
            assertTrue("periodCycles >= 1", spec.periodCycles >= 1)
            assertTrue("phaseOffset >= 0", spec.phaseOffset >= 0)
            assertTrue("phaseOffset < periodCycles", spec.phaseOffset < spec.periodCycles)
        }
    }

    @Test
    fun hotLanePidsArePolledEveryCycle() {
        // Drive-critical PIDs that must update every cycle.
        val hotLane = arrayOf("010D", "010C", "0149", "222414")
        for (command in hotLane) {
            val spec = findByCommand(command)
            assertEquals("Hot lane $command must have period 1", 1, spec.periodCycles)
            assertEquals("Hot lane $command phase must be 0", 0, spec.phaseOffset)
        }
    }

    @Test
    fun warmLanePidsUseEveryOtherCyclePhases() {
        val load = findByCommand("0104")
        val throttle = findByCommand("0111")
        val packVoltage = findByCommand("222429")
        assertEquals("load period", 2, load.periodCycles)
        assertEquals("ICE throttle period", 2, throttle.periodCycles)
        assertEquals("pack voltage period", 2, packVoltage.periodCycles)
        assertTrue(
            "load and ICE throttle phases must differ",
            load.phaseOffset != throttle.phaseOffset,
        )
        assertEquals(
            "pack voltage shares the load phase",
            load.phaseOffset,
            packVoltage.phaseOffset,
        )
    }

    @Test
    fun slowAndThermalPidsAreStaggered() {
        val atrv = findByCommand("ATRV")
        val soc = findByCommand("015B")
        val coolant = findByCommand("0105")
        val batteryTemp = findByCommand("22434F")
        assertEquals("ATRV period", 6, atrv.periodCycles)
        assertEquals("SOC period", 6, soc.periodCycles)
        assertTrue("slow-lane phases must differ", atrv.phaseOffset != soc.phaseOffset)
        assertEquals("coolant period", 12, coolant.periodCycles)
        assertEquals("batteryTemp period", 12, batteryTemp.periodCycles)
        assertTrue(
            "thermal-lane phases must differ",
            coolant.phaseOffset != batteryTemp.phaseOffset,
        )
    }

    @Test
    fun deepLaneKeepsScanValidatedContextOffTheFastPath() {
        for (
        command in
        arrayOf(
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
            "22437D",
        )
        ) {
            val spec = findByCommand(command)
            assertTrue(
                "deep-lane command $command must not poll faster than every 24 cycles",
                spec.periodCycles >= 24,
            )
        }
        assertEquals(120, findByCommand("2243A5").periodCycles)
        assertEquals(120, findByCommand("22437D").periodCycles)
        assertEquals(240, findByCommand("22119F").periodCycles)
        assertEquals(240, findByCommand("22119F01").periodCycles)
        assertEquals(240, findByCommand("01A6").periodCycles)
    }

    @Test
    fun motorDetailStaysOffTheHotPath() {
        for (command in arrayOf("222883", "222884", "222885", "222886")) {
            val spec = findByCommand(command)
            assertEquals("motor detail cadence $command", 12, spec.periodCycles)
        }
    }

    /**
     * Run 48 cycles and assert the exact send count per PID matches the declared cadence. This is
     * the schedule's contract — if the contract changes, this fails loudly.
     */
    @Test
    fun over48CyclesEachPidHitsItsDeclaredRate() {
        val sendCount = HashMap<String, Int>()
        for (cycle in 0 until 48) {
            for (spec in PidSchedule.dueOnCycle(cycle)) {
                sendCount.merge(spec.command, 1) { a, b -> a + b }
            }
        }
        // Hot lane (period 1) — exactly 48 sends in 48 cycles
        for (hotLane in arrayOf("010D", "010C", "0149", "222414")) {
            assertEquals(
                "$hotLane (hot lane) should fire on every cycle",
                48,
                sendCount[hotLane],
            )
        }
        // Warm lane (period 2) — 48/2 = 24 sends
        assertEquals("load every 2 cycles", 24, sendCount["0104"])
        assertEquals("ICE throttle every 2 cycles", 24, sendCount["0111"])
        assertEquals("pack voltage every 2 cycles", 24, sendCount["222429"])
        // Slow lane (period 6) — 48/6 = 8 sends
        assertEquals("ATRV every 6 cycles", 8, sendCount["ATRV"])
        assertEquals("SOC every 6 cycles", 8, sendCount["015B"])
        // Thermal lane (period 12) — 48/12 = 4 sends
        assertEquals("coolant every 12 cycles", 4, sendCount["0105"])
        assertEquals("batteryTemp every 12 cycles", 4, sendCount["22434F"])
        assertEquals("motor A current every 12 cycles", 4, sendCount["222883"])
        assertEquals("motor A voltage every 12 cycles", 4, sendCount["222885"])
        assertEquals("motor B current every 12 cycles", 4, sendCount["222884"])
        assertEquals("motor B voltage every 12 cycles", 4, sendCount["222886"])
        // Deep lane — scan-validated context stays rare enough not to slow the hot loop.
        for (
        deep24 in
        arrayOf(
            "0142",
            "011F",
            "010F",
            "22203F",
            "222889",
            "22436B",
            "22436C",
            "224373",
            "224531",
            "2243AF",
        )
        ) {
            assertEquals("$deep24 every 24 cycles", 2, sendCount[deep24])
        }
        for (
        deep48 in
        arrayOf(
            "012F",
            "015C",
            "221154",
            "222487",
            "221940",
            "22194001",
            "2241B2",
            "2241B4",
            "2241B6",
        )
        ) {
            assertEquals("$deep48 every 48 cycles", 1, sendCount[deep48])
        }
    }

    /**
     * The cycle count should stay roughly flat — no single cycle should poll both slow-lane PIDs or
     * both thermal-lane PIDs at once.
     */
    @Test
    fun noTwoSlowOrThermalLanePidsLandOnTheSameCycle() {
        for (cycle in 0 until 48) {
            val due = PidSchedule.dueOnCycle(cycle)
            val slowCount = countMatchingCommands(due, "ATRV", "015B")
            val thermalCount = countMatchingCommands(due, "0105", "22434F")
            assertTrue("cycle $cycle has >1 slow-lane PID", slowCount <= 1)
            assertTrue("cycle $cycle has >1 thermal-lane PID", thermalCount <= 1)
        }
    }

    @Test
    fun cycleZeroPollsAllPhaseZeroSpecs() {
        val due = PidSchedule.dueOnCycle(0)
        // Every spec with phaseOffset == 0 should fire on cycle 0.
        for (spec in PidSchedule.SPECS) {
            if (spec.phaseOffset == 0) {
                assertTrue(
                    "${spec.command} should be due on cycle 0",
                    due.any { it.command == spec.command },
                )
            }
        }
    }

    @Test
    fun negativeCycleNumberReturnsEmpty() {
        assertTrue(PidSchedule.dueOnCycle(-1).isEmpty())
        assertTrue(PidSchedule.dueOnCycle(-100).isEmpty())
    }

    @Test
    fun invalidSpecConstructionThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            PidSpec(null, Header.BROADCAST, 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PidSpec("010D", Header.BROADCAST, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PidSpec("010D", Header.BROADCAST, 4, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PidSpec("010D", Header.BROADCAST, 4, -1)
        }
    }

    // ---- Generalized Mode-01 same-cycle batching precondition -----------------------

    /**
     * The generalized Mode-01 batching (G2) groups any same-header Mode-01 PIDs that fall due on the
     * same cycle. This pins the schedule's precondition for that path: there exists a cycle on which
     * two non-hot broadcast Mode-01 PIDs (load 0104 + SOC 015B) coincide, so the engine has a real
     * multi-lane group to batch. Hot-lane batching is a separate, dedicated path.
     */
    @Test
    fun aMultiTierSameCycleMode01BatchGroupExists() {
        var foundCoincidence = false
        var cycle = 0
        while (cycle < 48 && !foundCoincidence) {
            val due = PidSchedule.dueOnCycle(cycle)
            val broadcastMode01 = ArrayList<String>()
            for (spec in due) {
                val hotLane = PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)
                val broadcastMode01Cmd =
                    spec.header == Header.BROADCAST &&
                        spec.command.length == 4 &&
                        spec.command.startsWith("01")
                if (broadcastMode01Cmd && !hotLane) {
                    broadcastMode01.add(spec.command)
                }
            }
            if (broadcastMode01.size >= 2) {
                foundCoincidence = true
                assertTrue(
                    "expected load in the multi-lane group on cycle $cycle",
                    broadcastMode01.contains("0104"),
                )
                assertTrue(
                    "expected SOC in the multi-lane group on cycle $cycle",
                    broadcastMode01.contains("015B"),
                )
            }
            cycle += 1
        }
        assertTrue(
            "schedule must produce at least one multi-lane same-cycle Mode-01 batch group",
            foundCoincidence,
        )
    }

    // ---- B7 Mode-01 multi-PID batch constants ---------------------------------------

    @Test
    fun mode01BatchCommands_matchEveryPidHexInTheSameOrder() {
        // The two parallel lists must stay in lockstep: same length, and the PID hex must
        // be the last 2 chars of each batch command. A future addition that breaks this
        // would corrupt the batched read.
        assertEquals(
            PidSchedule.MODE_01_BATCH_COMMANDS.size,
            PidSchedule.MODE_01_BATCH_PIDS_HEX.size,
        )
        for (i in PidSchedule.MODE_01_BATCH_COMMANDS.indices) {
            val command = PidSchedule.MODE_01_BATCH_COMMANDS[i]
            val pidHex = PidSchedule.MODE_01_BATCH_PIDS_HEX[i]
            assertEquals(
                "batch command $command must end in PID hex $pidHex",
                pidHex,
                command.substring(2),
            )
            assertEquals("01", command.substring(0, 2))
        }
    }

    @Test
    fun mode01BatchCommands_areAllTier1BroadcastSpecs() {
        // Batched PIDs must all be period=1, broadcast header — otherwise the engine's
        // "all batch commands are due" predicate would never fire and the optimization
        // wouldn't engage.
        for (command in PidSchedule.MODE_01_BATCH_COMMANDS) {
            var spec: PidSpec? = null
            for (candidate in PidSchedule.SPECS) {
                if (command == candidate.command) {
                    spec = candidate
                    break
                }
            }
            assertNotNull("batch command $command missing from SPECS", spec)
            assertEquals("batch command $command must be period=1", 1, spec!!.periodCycles)
            assertEquals(
                "batch command $command must be broadcast header",
                Header.BROADCAST,
                spec.header,
            )
        }
    }

    private companion object {
        private fun findByCommand(command: String): PidSpec {
            for (spec in PidSchedule.SPECS) {
                if (spec.command == command) {
                    return spec
                }
            }
            throw AssertionError("no spec for command $command")
        }

        private fun countMatchingCommands(
            specs: List<PidSpec>,
            vararg commands: String,
        ): Int {
            var count = 0
            for (spec in specs) {
                for (command in commands) {
                    if (spec.command == command) {
                        count += 1
                        break
                    }
                }
            }
            return count
        }
    }
}
