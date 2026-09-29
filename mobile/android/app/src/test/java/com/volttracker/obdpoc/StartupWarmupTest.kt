package com.volttracker.obdpoc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupWarmupTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun warmLoadsThePrefsFileSoLaterReadsAreImmediate() {
        context
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("probe", "kept")
            .commit()

        StartupWarmup.warm(context)

        assertEquals(
            "kept",
            context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).getString("probe", null),
        )
    }

    @Test
    fun startHandsTheTaskToTheExecutor() {
        var ran = false
        StartupWarmup.start({ it.run() }) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun startForAContextQueuesTheRealWarmUpWithoutBlocking() {
        StartupWarmup.start(context)
    }

    @Test
    fun aRejectedExecutorIsSwallowed() {
        val rejecting = Executor { throw RejectedExecutionException("shut down") }
        StartupWarmup.start(rejecting) { error("must not run") }
    }
}
