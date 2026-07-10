package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [MaintenanceDueNotifier] (M2): the app-open orchestration that reads the maintenance
 * log JSON, runs the evaluator, posts via [EventNotifier], and persists the fired crossing
 * signatures. Robolectric is used only because `org.json` parsing needs the Android JSON runtime;
 * the collaborators are all lambdas so no Activity/Context is involved.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceDueNotifierTest {
    private val now = 1_700_000_000_000L

    private fun logOf(vararg rows: JSONObject): JSONArray {
        val arr = JSONArray()
        rows.forEach { arr.put(it) }
        return arr
    }

    private fun row(
        id: Long,
        type: String,
        odometerKm: Any? = JSONObject.NULL,
        createdAtMs: Long = now,
        intervalKm: Any? = JSONObject.NULL,
        intervalMonths: Any? = JSONObject.NULL,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", type)
            .put("odometerKm", odometerKm ?: JSONObject.NULL)
            .put("createdAtMs", createdAtMs)
            .put("intervalKm", intervalKm ?: JSONObject.NULL)
            .put("intervalMonths", intervalMonths ?: JSONObject.NULL)

    private class Harness(
        enabled: Boolean = true,
        val log: JSONArray? = JSONArray(),
        val odometerKm: Double? = null,
        var notified: Set<String> = emptySet(),
        val nowMs: Long,
        val postSucceeds: Boolean = true,
    ) {
        val isEnabled = enabled
        val posted = ArrayList<EventNotificationDecider.Event>()
        var persisted: Set<String>? = null

        fun notifier(): MaintenanceDueNotifier =
            MaintenanceDueNotifier(
                isEnabled = { isEnabled },
                readMaintenanceLogJson = { log },
                readLatestOdometerKm = { odometerKm },
                readNotifiedSignatures = { notified },
                persistNotifiedSignatures = { persisted = it },
                notify = {
                    posted.add(it)
                    postSucceeds
                },
                now = { nowMs },
            )
    }

    @Test
    fun disabledToggleIsANoOp() {
        val h =
            Harness(
                enabled = false,
                log = logOf(row(7L, "Oil change", odometerKm = 10_000.0, intervalKm = 12_000.0)),
                odometerKm = 30_000.0,
                nowMs = now,
            )

        val fired = h.notifier().checkOnAppOpen()

        assertEquals(0, fired)
        assertTrue("a disabled toggle must post nothing", h.posted.isEmpty())
        assertEquals("a disabled toggle must not touch persisted state", null, h.persisted)
    }

    @Test
    fun newlyOverdueEntryPostsAndPersistsItsSignature() {
        val h =
            Harness(
                log = logOf(row(7L, "Oil change", odometerKm = 10_000.0, intervalKm = 12_000.0)),
                odometerKm = 23_000.0,
                nowMs = now,
            )

        val fired = h.notifier().checkOnAppOpen()

        assertEquals(1, fired)
        assertEquals(1, h.posted.size)
        val event = h.posted.first() as EventNotificationDecider.Event.MaintenanceDue
        assertEquals("Oil change", event.serviceType)
        assertEquals(7L, event.entryId)
        assertEquals(setOf("7:overdue"), h.persisted)
    }

    @Test
    fun alreadyNotifiedCrossingPostsNothingAndLeavesStateUntouched() {
        val h =
            Harness(
                log = logOf(row(7L, "Oil change", odometerKm = 10_000.0, intervalKm = 12_000.0)),
                odometerKm = 23_000.0,
                notified = setOf("7:overdue"),
                nowMs = now,
            )

        val fired = h.notifier().checkOnAppOpen()

        assertEquals(0, fired)
        assertTrue(h.posted.isEmpty())
        assertEquals("no new crossing -> no persist write", null, h.persisted)
    }

    @Test
    fun failedPlatformPostDoesNotConsumeTheReminderSignature() {
        val h =
            Harness(
                log = logOf(row(7L, "Oil change", odometerKm = 10_000.0, intervalKm = 12_000.0)),
                odometerKm = 23_000.0,
                nowMs = now,
                postSucceeds = false,
            )

        assertEquals(0, h.notifier().checkOnAppOpen())
        assertEquals(1, h.posted.size)
        assertEquals("a skipped notification must remain retryable", null, h.persisted)
    }

    @Test
    fun firedSignaturesAreUnionedWithThePriorSet() {
        // A pre-existing unrelated signature must survive the persist write alongside the new one.
        val h =
            Harness(
                log = logOf(row(7L, "Oil change", odometerKm = 10_000.0, intervalKm = 12_000.0)),
                odometerKm = 23_000.0,
                notified = setOf("99:overdue"),
                nowMs = now,
            )

        h.notifier().checkOnAppOpen()

        assertEquals(setOf("99:overdue", "7:overdue"), h.persisted)
    }

    @Test
    fun unsetIntervalRowNeverFires() {
        // odometerKm/intervalKm left as JSON null (a plain history row) must not fire.
        val h =
            Harness(
                log = logOf(row(1L, "Tire rotation")),
                odometerKm = 999_999.0,
                nowMs = now,
            )

        val fired = h.notifier().checkOnAppOpen()

        assertEquals(0, fired)
        assertTrue(h.posted.isEmpty())
    }

    @Test
    fun nullMaintenanceLogIsANoOp() {
        val h = Harness(log = null, odometerKm = 30_000.0, nowMs = now)

        val fired = h.notifier().checkOnAppOpen()

        assertEquals(0, fired)
        assertEquals(null, h.persisted)
    }
}
