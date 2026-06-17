package com.volttracker.obdpoc.startupbenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupToDashboardReady() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = STARTUP_ITERATIONS,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
            assertTrue(
                "Dashboard did not report ready within ${DASHBOARD_READY_TIMEOUT_MS}ms.",
                device.wait(
                    Until.hasObject(By.desc(DASHBOARD_READY_DESCRIPTION)),
                    DASHBOARD_READY_TIMEOUT_MS,
                ),
            )
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.volttracker.obdpoc"
        const val DASHBOARD_READY_DESCRIPTION = "VoltTracker dashboard ready"
        const val DASHBOARD_READY_TIMEOUT_MS = 10_000L
        const val STARTUP_ITERATIONS = 5
    }
}
