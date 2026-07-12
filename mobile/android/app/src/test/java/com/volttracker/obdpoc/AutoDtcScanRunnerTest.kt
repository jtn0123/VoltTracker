package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.AutoDtcScanRunner
import com.volttracker.obdpoc.engine.ObdPollingEngine
import com.volttracker.obdpoc.service.ObdService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Focused tests for the lightweight on-connect Mode 03 DTC reader. */
class AutoDtcScanRunnerTest {
    @Test
    fun completedScanSendsGenericHeaderThenMode03AndDedupesCodes() {
        val engine = FakeEngine("43 03 01 33 01 33 25 A2\r>")

        val result = AutoDtcScanRunner(engine).readGenericDtcScan()

        assertTrue(result.completed)
        assertEquals(listOf("P0133", "P25A2"), result.codes)
        assertEquals(listOf("ATSH7DF" to 1800L, "03" to 3500L), engine.commands)
    }

    @Test
    fun completedCleanCarScanCanReturnNoCodes() {
        val engine = FakeEngine("43 00\r>")

        val result = AutoDtcScanRunner(engine).readGenericDtcScan()

        assertTrue("a clean Mode 03 reply is still a completed scan", result.completed)
        assertTrue(result.codes.isEmpty())
    }

    @Test
    fun elmErrorReplyIsIncompleteAndCannotMasqueradeAsACleanScan() {
        val result = AutoDtcScanRunner(FakeEngine("CAN ERROR\r>")).readGenericDtcScan()

        assertFalse(result.completed)
        assertTrue(result.codes.isEmpty())
    }

    @Test
    fun readGenericDtcCodesReturnsOnlyCodesForCompatibilityCaller() {
        val engine = FakeEngine("43 01 C0 73 00 00\r>")

        assertEquals(listOf("U0073"), AutoDtcScanRunner(engine).readGenericDtcCodes())
    }

    @Test
    fun ioFailureReturnsIncompleteWithoutThrowing() {
        val engine = FakeEngine("43 00\r>").apply { throwIo = true }

        val result = AutoDtcScanRunner(engine).readGenericDtcScan()

        assertFalse(result.completed)
        assertTrue(result.codes.isEmpty())
        assertEquals(listOf("ATSH7DF" to 1800L), engine.commands)
    }

    @Test
    fun runtimeFailureReturnsIncompleteWithoutThrowing() {
        val engine = FakeEngine("43 00\r>").apply { throwRuntime = true }

        val result = AutoDtcScanRunner(engine).readGenericDtcScan()

        assertFalse(result.completed)
        assertTrue(result.codes.isEmpty())
    }

    private class FakeEngine(
        private val mode03Response: String,
    ) : ObdPollingEngine(ObdService()) {
        val commands = ArrayList<Pair<String?, Long>>()
        var throwIo = false
        var throwRuntime = false

        override fun sendRecoverableCommand(
            command: String?,
            timeoutMs: Long,
        ): String {
            commands.add(command to timeoutMs)
            if (throwIo) {
                throw IOException("adapter asleep")
            }
            if (throwRuntime) {
                throw IllegalStateException("adapter closed")
            }
            return if (command == "03") mode03Response else "OK\r>"
        }
    }
}
