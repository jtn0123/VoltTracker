package com.volttracker.obdpoc

import android.os.Looper
import com.volttracker.obdpoc.data.DatabaseCorruption
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The native launcher tells the owner when damaged history was set aside, until they dismiss it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryResetNoticeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val liveFile: File get() = context.getDatabasePath(VoltTrackerDb.DATABASE_NAME)

    @Before
    fun setUp() {
        // Same native-SQLite warm-up as the other Activity tests (see MainActivityWebViewLifecycleTest).
        ObdLocalStore(context).apply {
            getRecentSessions(1)
            close()
        }
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        DatabaseCorruption.dismissNotice(context)
    }

    @After
    fun tearDown() {
        while (ClassicDashboardPresence.isAlive()) ClassicDashboardPresence.onDestroyed()
        DatabaseCorruption.setAsideCopies(liveFile).forEach { DatabaseCorruption.discard(context, it) }
        context.deleteDatabase(VoltTrackerDb.DATABASE_NAME)
    }

    @Test
    fun theNativeLauncherShowsAResetFoundBeforeOrWhileItIsShowing() {
        setAsideLiveDatabase(5_000L)
        val controller = Robolectric.buildActivity(ComposeDashboardActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(5_000L, activity.uiState().historyResetAtMs)

        setAsideLiveDatabase(6_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "a reset found while the screen shows appears at once",
            6_000L,
            activity.uiState().historyResetAtMs,
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun dismissingTheNoticeClearsItForGood() {
        setAsideLiveDatabase(5_000L)
        val notice = HistoryResetNotice(context)

        notice.start()
        assertEquals(5_000L, notice.atMs.value)
        notice.dismiss()
        notice.stop()

        assertNull(notice.atMs.value)
        assertNull(DatabaseCorruption.pendingNoticeAtMs(context))
        assertEquals("dismissing keeps the damaged copy", 1, DatabaseCorruption.setAsideCopies(liveFile).size)
    }

    private fun setAsideLiveDatabase(atMs: Long) {
        context.deleteDatabase(VoltTrackerDb.DATABASE_NAME)
        liveFile.parentFile?.mkdirs()
        liveFile.writeText("damaged")
        DatabaseCorruption.setAside(context, liveFile, atMs)
    }
}
