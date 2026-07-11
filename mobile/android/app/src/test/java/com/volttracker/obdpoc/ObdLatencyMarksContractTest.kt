package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Protects the OBD latency-mark contract (audit item G1), in the same spirit as
 * [EmulatorSmokeContractTest]:
 *
 *  - `tools/perf/check_obd_latency.py` parses `VoltStartup` logcat lines by the literal
 *    `obd_*` mark names emitted through [StartupTrace]. If a mark constant is renamed without
 *    updating the parser, the latency gate would go green while measuring nothing.
 *  - `scripts/emulator-smoke.sh` runs that parser with `--require demo` right after it starts
 *    the native demo session, which is what makes the connect→first-sample marks capturable
 *    (and CI-gated) without a car. If the smoke stops invoking the check, the marks stop
 *    being enforced anywhere.
 *
 * This fast JVM test fails the build when either side drifts.
 */
class ObdLatencyMarksContractTest {
    @Test
    fun latencyCheckScriptKnowsEveryObdMarkName() {
        val script = locate("tools/perf/check_obd_latency.py")
        val contents = script.readText()
        val marks =
            mapOf(
                "OBD_CONNECT_REQUEST" to StartupTrace.OBD_CONNECT_REQUEST,
                "OBD_SOCKET_CONNECTED" to StartupTrace.OBD_SOCKET_CONNECTED,
                "OBD_ELM_INIT_START" to StartupTrace.OBD_ELM_INIT_START,
                "OBD_ELM_INIT_END" to StartupTrace.OBD_ELM_INIT_END,
                "OBD_FIRST_SAMPLE" to StartupTrace.OBD_FIRST_SAMPLE,
                "OBD_SCAN_START" to StartupTrace.OBD_SCAN_START,
                "OBD_SCAN_STAGE" to StartupTrace.OBD_SCAN_STAGE,
                "OBD_SCAN_COMPLETE" to StartupTrace.OBD_SCAN_COMPLETE,
            )
        for ((constant, value) in marks) {
            assertTrue(
                "check_obd_latency.py must reference the current value of StartupTrace.$constant " +
                    "(\"$value\") or the latency gate silently measures nothing; " +
                    "script: ${script.absolutePath}",
                contents.contains("\"$value\""),
            )
        }
    }

    @Test
    fun emulatorSmokeRunsTheLatencyCheckOnTheDemoSession() {
        val script = locate("scripts/emulator-smoke.sh")
        val contents = script.readText()
        assertTrue(
            "emulator-smoke.sh must run tools/perf/check_obd_latency.py after starting the demo " +
                "session so the obd_* marks stay capturable and gated without a car; " +
                "script: ${script.absolutePath}",
            contents.contains("tools/perf/check_obd_latency.py"),
        )
        assertTrue(
            "emulator-smoke.sh must pass --require demo so a dropped/renamed mark fails the smoke " +
                "instead of the check trivially passing on an empty log; script: ${script.absolutePath}",
            contents.contains("--require demo"),
        )
    }

    /** Resolves a repo path by walking up from the test working directory (see EmulatorSmokeContractTest). */
    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative searching upward from " + System.getProperty("user.dir"))
    }
}
