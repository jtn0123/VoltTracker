package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Protects the emulator-smoke log-string contract. (Introduced as item "D5" of a grade-codebase
 * audit pass — those reports are gitignored, so this doc is the durable description; the audit-ID
 * convention is described in the root CONTRIBUTING.md.)
 *
 * `scripts/emulator-smoke.sh` greps logcat for the literal handshake prefix as its ONLY positive
 * signal. That prefix is emitted by [MainActivity.onDashboardReady] and pinned in the source-of-
 * truth constant [MainActivity.DASHBOARD_READY_LOG]. If either side is renamed independently, the
 * smoke would pass while testing nothing. This fast JVM test fails the build instead:
 *
 *  - it asserts the script still references `MainActivity.DASHBOARD_READY_LOG` (so a rename of the
 *    constant must be reflected in the script), and
 *  - it asserts the script's grep still matches the constant's *value* (so the live logcat grep and
 *    the emitted [Log.i] line stay in sync).
 */
class EmulatorSmokeContractTest {
    @Test
    fun smokeScriptReferencesTheHandshakeConstantAndItsValue() {
        val script = locateSmokeScript()
        val contents = script.readText()

        assertTrue(
            "emulator-smoke.sh must reference MainActivity.DASHBOARD_READY_LOG so a constant rename " +
                "is caught here; script: ${script.absolutePath}",
            contents.contains("MainActivity.DASHBOARD_READY_LOG"),
        )
        assertTrue(
            "emulator-smoke.sh must grep for the current value of MainActivity.DASHBOARD_READY_LOG " +
                "(\"${MainActivity.DASHBOARD_READY_LOG}\") so the live logcat check still matches the " +
                "emitted log line; script: ${script.absolutePath}",
            contents.contains(MainActivity.DASHBOARD_READY_LOG),
        )
    }

    /**
     * Resolves `scripts/emulator-smoke.sh` by walking up from the test working directory, which is
     * the `mobile/android` module dir under Gradle but may be `app/` in some IDE runners.
     */
    private fun locateSmokeScript(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "scripts/emulator-smoke.sh")
            if (candidate.isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate scripts/emulator-smoke.sh searching upward from " +
                System.getProperty("user.dir"),
        )
    }
}
