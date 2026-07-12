package com.volttracker.obdpoc

import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Pins [BackupRestoreProgressPresenter]'s dashboard-facing contract: percent resolution on
 * [BackupRestoreProgressPresenter.show], the idle reset on hide, and the worker-side emitter's
 * throttling / completion / cancellation behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreProgressPresenterTest {
    private lateinit var controller: ActivityController<HarnessActivity>
    private lateinit var activity: HarnessActivity
    private lateinit var presenter: BackupRestoreProgressPresenter

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        activity = controller.get()
        presenter = BackupRestoreProgressPresenter(activity) { false }
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // Activity teardown can race Robolectric background work; not part of these assertions.
        }
    }

    @Test
    fun showCoercesAnExplicitPercentIntoTheDisplayableRange() {
        presenter.show("Backing up", "detail", percent = 250, operation = "backup")

        val event = activity.progressEvents.single()
        assertEquals(true, event.visible)
        assertEquals(true, event.busy)
        assertEquals("busy", event.tone)
        assertEquals(100, event.percent)
        assertEquals("backup", event.operation)
    }

    @Test
    fun showTreatsANonBusyOkCardAsComplete() {
        presenter.show("Restore complete", "detail", busy = false, tone = "ok")

        assertEquals(100, activity.progressEvents.single().percent)
    }

    @Test
    fun showDerivesPercentFromByteProgressWhenNoneIsGiven() {
        presenter.show("Restoring", "detail", bytesDone = 25L, bytesTotal = 100L)

        assertEquals(25, activity.progressEvents.single().percent)
    }

    @Test
    fun hidePublishesTheIdleReset() {
        presenter.hide()

        val event = activity.progressEvents.single()
        assertEquals(false, event.visible)
        assertEquals(false, event.busy)
        assertEquals("idle", event.tone)
        assertEquals(-1, event.percent)
    }

    @Test
    fun emitterPublishesRowProgressWithPhaseTitleAndOperation() {
        val emitter = presenter.emitter("Merging backup", "fallback", "restore")

        emitter.onMergeProgress("sessions", rowsDone = 0L, rowsTotal = 10L)

        val event = activity.progressEvents.single()
        assertEquals("Merging backup", event.title)
        assertEquals("fallback", event.detail)
        assertEquals("sessions", event.phase)
        assertEquals("restore", event.operation)
        assertEquals(0, event.percent)
        assertEquals(-1L, event.etaSeconds)
    }

    @Test
    fun emitterThrottlesBackToBackIntermediateUpdatesButAlwaysPublishesCompletion() {
        val emitter = presenter.emitter("Merging backup", "fallback", "restore")

        emitter.onMergeProgress("sessions", rowsDone = 1L, rowsTotal = 10L)
        emitter.onMergeProgress("sessions", rowsDone = 2L, rowsTotal = 10L)
        emitter.onMergeProgress("sessions", rowsDone = 10L, rowsTotal = 10L)

        assertEquals(
            "second update lands inside the throttle window; completion must bypass it",
            listOf(10, 100),
            activity.progressEvents.map { it.percent },
        )
    }

    @Test
    fun emitterForwardsDataBackupSnapshotsWithByteDerivedPercent() {
        val emitter = presenter.emitter("Reading backup", "fallback", "restore")

        emitter.onDataBackupProgress(
            DataBackup.ProgressSnapshot("copy", "Copying", bytesDone = 50L, bytesTotal = 100L),
        )

        val event = activity.progressEvents.single()
        assertEquals("copy", event.phase)
        assertEquals("Copying", event.detail)
        assertEquals(50, event.percent)
    }

    @Test
    fun cancelledEmitterStaysSilent() {
        val cancelled = BackupRestoreProgressPresenter(activity) { true }
        val emitter = cancelled.emitter("Merging backup", "fallback", "restore")

        emitter.onMergeProgress("sessions", rowsDone = 10L, rowsTotal = 10L)

        assertTrue(activity.progressEvents.isEmpty())
    }

    /** Minimal MainActivity that records progress events instead of driving the WebView. */
    class HarnessActivity : MainActivity() {
        @JvmField val progressEvents = ArrayList<ProgressEvent>()

        override fun onCreate(savedInstanceState: Bundle?) {
            // No store or WebView needed; the presenter only publishes.
        }

        override fun publishRestoreProgress(
            visible: Boolean,
            busy: Boolean,
            title: String?,
            detail: String?,
            tone: String?,
            phase: String?,
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            percent: Int,
            etaSeconds: Long,
            operation: String?,
        ) {
            progressEvents.add(
                ProgressEvent(visible, busy, title, detail, tone, phase, percent, etaSeconds, operation),
            )
        }
    }

    data class ProgressEvent(
        val visible: Boolean,
        val busy: Boolean,
        val title: String?,
        val detail: String?,
        val tone: String?,
        val phase: String?,
        val percent: Int,
        val etaSeconds: Long,
        val operation: String?,
    )
}
