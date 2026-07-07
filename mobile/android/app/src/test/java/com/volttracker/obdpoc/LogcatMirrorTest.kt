package com.volttracker.obdpoc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Direct coverage for the logcat-to-rolling-log facade used by diagnostics collection. */
class LogcatMirrorTest {
    private lateinit var dir: File
    private lateinit var rollingLog: RollingAppLog
    private lateinit var mirror: LogcatMirror

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("logcatmirror").toFile()
        rollingLog = RollingAppLog(dir)
        mirror = LogcatMirror(rollingLog)
    }

    @After
    fun tearDown() {
        rollingLog.close()
        deleteRec(dir)
    }

    @Test
    fun everyLevelWritesToTheRollingLog() {
        mirror.d("diag", "debug detail")
        mirror.i("diag", "info detail")
        mirror.w("diag", "warning detail")
        mirror.e("diag", "error detail")

        val contents = rollingLog.liveFile().readText()
        assertTrue(contents.contains("D diag: debug detail"))
        assertTrue(contents.contains("I diag: info detail"))
        assertTrue(contents.contains("W diag: warning detail"))
        assertTrue(contents.contains("E diag: error detail"))
    }

    @Test
    fun throwableOverloadsWriteEntriesToTheRollingLog() {
        mirror.w("diag", "warn failed", IllegalStateException("warn boom"))
        mirror.e("diag", "error failed", IllegalArgumentException("error boom"))

        val contents = rollingLog.liveFile().readText()
        assertTrue(contents.contains("W diag: warn failed"))
        assertTrue(contents.contains("E diag: error failed"))
    }

    @Test
    fun closeReleasesTheRollingLogWriterAndDropsLateWrites() {
        mirror.i("diag", "before close")

        mirror.close()
        mirror.i("diag", "after close")

        val contents = rollingLog.liveFile().readText()
        assertTrue(contents.contains("before close"))
        assertFalse(contents.contains("after close"))
    }

    private companion object {
        fun deleteRec(file: File?) {
            if (file == null) return
            if (file.isDirectory) {
                file.listFiles()?.forEach(::deleteRec)
            }
            @Suppress("UNUSED_VARIABLE")
            val ignored = file.delete()
        }
    }
}
