package com.volttracker.obdpoc

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Default uncaught-exception handler that appends the crashing thread's name and the full stack
 * trace (bounded) to the rolling app log before chaining to the previously installed handler —
 * so Android's normal crash dialog/reporting still happens, but the crash is no longer invisible
 * in the diagnostics bundle (report item B4: the app log captured everything *except* the one
 * event that kills the process).
 *
 * Crash-safety contract: the logging half is wrapped so nothing it throws can swallow the chain
 * call; [RollingAppLog.write] flushes each line synchronously, so the trace is on disk before the
 * previous handler tears the process down.
 */
class UncaughtCrashLogger(
    private val appendToAppLog: (line: String) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        try {
            for (line in formatCrash(thread, error)) {
                appendToAppLog(line)
            }
        } catch (_: Throwable) {
            // Never let crash *logging* break crash *handling*: fall through to the chain.
        } finally {
            // Chain unconditionally so Android's default handler still shows the crash dialog
            // and kills the process. (At install time the platform default is always non-null;
            // the null guard is for pathological embedders.)
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "UncaughtCrash"

        /**
         * Upper bound on the persisted stack-trace text. Real traces (even deeply nested
         * `Caused by` chains) are a few KB; the cap only defends against pathological cases
         * like an exception whose message embeds a huge payload.
         */
        const val MAX_STACK_CHARS: Int = 16_000

        /**
         * Installs a [UncaughtCrashLogger] writing FATAL-level lines to [appLog] and chaining to
         * the current default handler. Idempotent: a second call (e.g. a re-created Application
         * in tests) does not stack a second logger in front of the first.
         */
        @JvmStatic
        fun install(appLog: RollingAppLog) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            // Compared by class NAME, not `is`: under Robolectric each sandbox classloader has
            // its own UncaughtCrashLogger class, and an instanceof check would re-install (and
            // chain) one logger per sandbox against the JVM-global default handler.
            if (current != null && current.javaClass.name == UncaughtCrashLogger::class.java.name) {
                return
            }
            Thread.setDefaultUncaughtExceptionHandler(
                UncaughtCrashLogger({ line -> appLog.write("F", TAG, line) }, current),
            )
        }

        /**
         * Renders `header + stack trace` as individual log lines (one per stack frame, so the
         * rolling log stays line-oriented and greppable), truncating past [MAX_STACK_CHARS].
         */
        fun formatCrash(
            thread: Thread,
            error: Throwable,
        ): List<String> {
            val stackText = StringWriter()
            error.printStackTrace(PrintWriter(stackText))
            var stack = stackText.toString()
            var truncationNote: String? = null
            if (stack.length > MAX_STACK_CHARS) {
                truncationNote = "... [stack truncated; ${stack.length - MAX_STACK_CHARS} chars dropped]"
                stack = stack.substring(0, MAX_STACK_CHARS)
            }
            val lines = ArrayList<String>()
            lines.add("FATAL uncaught exception on thread \"${thread.name}\"")
            stack.lineSequence().filter { it.isNotBlank() }.forEach { lines.add(it.trimEnd()) }
            if (truncationNote != null) {
                lines.add(truncationNote)
            }
            return lines
        }
    }
}
