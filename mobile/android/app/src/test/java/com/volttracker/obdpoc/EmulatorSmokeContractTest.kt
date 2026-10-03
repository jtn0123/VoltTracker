package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
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
     * The Compose phase's only positive signals are the debug VoltStartup marks named by
     * [StartupTrace.COMPOSE_SCREEN] and [StartupTrace.COMPOSE_FIRST_TELEMETRY]. Renaming either
     * side alone would leave the smoke waiting for a mark nobody logs (red) or, worse, grepping a
     * stale name that some other line happens to contain.
     */
    @Test
    fun smokeScriptWaitsForTheComposeMarks() {
        val contents = locate("scripts/emulator-smoke.sh").readText()

        for (
        (name, value) in
        listOf(
            "StartupTrace.COMPOSE_SCREEN" to StartupTrace.COMPOSE_SCREEN,
            "StartupTrace.COMPOSE_FIRST_TELEMETRY" to StartupTrace.COMPOSE_FIRST_TELEMETRY,
        )
        ) {
            assertTrue("emulator-smoke.sh must reference $name", contents.contains(name))
            assertTrue("emulator-smoke.sh must use the value of $name (\"$value\")", contents.contains("\"$value\""))
        }
    }

    /**
     * The classic phase taps the bottom nav by geometry: NAV_COUNT equal slots across the pill. The
     * script kept 7 slots after the nav shrank to 6 buttons, so its 4th tap landed on a button
     * boundary. Pin the count and the tapped labels to the template's data-nav buttons.
     */
    @Test
    fun smokeScriptTapsEveryClassicNavButtonInOrder() {
        val script = locate("scripts/emulator-smoke.sh").readText()
        val template = locate("app/src/main/dashboard-src/index.template.html").readText()
        val navButtons = Regex("""data-nav="([a-z-]+)"""").findAll(template).map { it.groupValues[1] }.toList()

        val navCount =
            Regex("""(?m)^NAV_COUNT=(\d+)$""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertEquals("NAV_COUNT must equal the template's data-nav button count", navButtons.size, navCount)
        val tapped =
            Regex("""(?m)^tap_bottom_nav (\d+) ([a-z-]+) """)
                .findAll(script)
                .map { it.groupValues[1].toInt() to it.groupValues[2] }
                .toList()
        assertEquals(
            "the smoke must tap each data-nav button once, in order",
            navButtons.withIndex().map {
                it.index to
                    it.value
            },
            tapped,
        )
    }

    private fun locateSmokeScript(): File = locate("scripts/emulator-smoke.sh")

    /**
     * Resolves a path under `mobile/android` by walking up from the test working directory, which
     * is the `mobile/android` module dir under Gradle but may be `app/` in some IDE runners.
     */
    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
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
