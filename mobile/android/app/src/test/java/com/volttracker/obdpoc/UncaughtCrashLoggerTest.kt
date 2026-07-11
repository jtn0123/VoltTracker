package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM coverage for report item B4: uncaught exceptions must land in the rolling app log
 * (thread name + bounded stack trace) and then chain to the previously installed default handler
 * so Android's normal crash flow still happens — even when the logging half itself blows up.
 */
class UncaughtCrashLoggerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var error: Throwable? = null
        var calls = 0

        override fun uncaughtException(
            t: Thread,
            e: Throwable,
        ) {
            calls += 1
            thread = t
            error = e
        }
    }

    @Test
    fun crashIsLoggedWithThreadNameAndStackThenChained() {
        val lines = ArrayList<String>()
        val previous = RecordingHandler()
        val logger = UncaughtCrashLogger({ lines.add(it) }, previous)
        val boom = IllegalStateException("engine fell out")
        val thread = Thread({}, "poller-3")

        logger.uncaughtException(thread, boom)

        assertTrue(
            "the header must carry the crashing thread's name: $lines",
            lines.first().contains("FATAL uncaught exception on thread \"poller-3\""),
        )
        assertTrue(
            "the exception type and message must be present: $lines",
            lines.any { it.contains("IllegalStateException") && it.contains("engine fell out") },
        )
        assertTrue(
            "stack frames must be present: $lines",
            lines.any { it.trimStart().startsWith("at ") },
        )
        assertEquals("the previous handler must be chained exactly once", 1, previous.calls)
        assertSame(thread, previous.thread)
        assertSame(boom, previous.error)
    }

    @Test
    fun causedByChainsAreCaptured() {
        val lines = ArrayList<String>()
        val logger = UncaughtCrashLogger({ lines.add(it) }, RecordingHandler())
        val boom = RuntimeException("outer", java.io.IOException("disk full"))

        logger.uncaughtException(Thread.currentThread(), boom)

        assertTrue(
            "the cause must be captured: $lines",
            lines.any { it.contains("Caused by") && it.contains("disk full") },
        )
    }

    @Test
    fun hugeStacksAreTruncatedWithANote() {
        val hugeMessage = "x".repeat(UncaughtCrashLogger.MAX_STACK_CHARS * 2)
        val lines = UncaughtCrashLogger.formatCrash(Thread.currentThread(), RuntimeException(hugeMessage))

        val total = lines.sumOf { it.length }
        assertTrue(
            "persisted crash text must stay bounded (was $total chars)",
            total <= UncaughtCrashLogger.MAX_STACK_CHARS + 256,
        )
        assertTrue(
            "truncation must be visible in the log: ${lines.last()}",
            lines.last().contains("stack truncated"),
        )
    }

    @Test
    fun normalStacksAreNotTruncated() {
        val lines = UncaughtCrashLogger.formatCrash(Thread.currentThread(), RuntimeException("small"))

        assertFalse(lines.any { it.contains("stack truncated") })
    }

    @Test
    fun aThrowingLogSinkStillChainsToThePreviousHandler() {
        val previous = RecordingHandler()
        val logger =
            UncaughtCrashLogger({ throw RuntimeException("log write exploded") }, previous)

        logger.uncaughtException(Thread.currentThread(), IllegalStateException("original crash"))

        assertEquals("crash logging failures must never swallow the chain", 1, previous.calls)
        assertTrue(previous.error is IllegalStateException)
    }

    @Test
    fun aNullPreviousHandlerIsTolerated() {
        val lines = ArrayList<String>()
        val logger = UncaughtCrashLogger({ lines.add(it) }, null)

        logger.uncaughtException(Thread.currentThread(), RuntimeException("no chain"))

        assertTrue("the crash is still logged", lines.isNotEmpty())
    }

    @Test
    fun installWritesFlushedLinesToTheRollingLogAndChains() {
        val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val previous = RecordingHandler()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val logDir = tmp.newFolder("app-log")
            val appLog = RollingAppLog(logDir)

            UncaughtCrashLogger.install(appLog)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(installed)
            assertTrue(installed is UncaughtCrashLogger)
            installed!!.uncaughtException(Thread.currentThread(), IllegalStateException("kaboom"))

            // RollingAppLog flushes per line, so the trace is durable before the chain fires.
            val logged = appLog.liveFile().readText()
            assertTrue("crash header must be on disk: $logged", logged.contains("FATAL uncaught exception"))
            assertTrue("stack must be on disk: $logged", logged.contains("kaboom"))
            assertTrue("lines carry the FATAL level marker: $logged", logged.contains(" F UncaughtCrash: "))
            assertEquals("the pre-install handler still runs", 1, previous.calls)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        }
    }

    @Test
    fun installIsIdempotent() {
        val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(RecordingHandler())
            val appLog = RollingAppLog(tmp.newFolder("app-log-2"))

            UncaughtCrashLogger.install(appLog)
            val first = Thread.getDefaultUncaughtExceptionHandler()
            UncaughtCrashLogger.install(appLog)

            assertSame(
                "a second install must not stack a second logger",
                first,
                Thread.getDefaultUncaughtExceptionHandler(),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        }
    }
}
