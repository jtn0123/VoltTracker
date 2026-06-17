package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [MaintenanceDueEvaluator] (M2) — the riskiest logic. Pins the due/overdue
 * math ported from `storage-status.ts#maintenanceDue` and the fire-once crossing-signature
 * behaviour: not-due, due-soon-but-not-overdue, newly-overdue-by-km, newly-overdue-by-months,
 * already-notified-so-no-refire, and an entry with no interval (never fires).
 */
class MaintenanceDueEvaluatorTest {
    private val evaluator = MaintenanceDueEvaluator()

    // A fixed "now" so the months math is deterministic.
    private val now = 1_700_000_000_000L
    private val msPerDay = 86_400_000L
    private val avgDaysPerMonth = 30.4375

    private fun entry(
        id: Long = 1L,
        type: String = "Oil change",
        odometerKm: Double? = null,
        createdAtMs: Long = now,
        intervalKm: Double? = null,
        intervalMonths: Int? = null,
    ) = MaintenanceDueEvaluator.Entry(id, type, odometerKm, createdAtMs, intervalKm, intervalMonths)

    @Test
    fun notDueWhenComfortablyAheadOnBothIntervals() {
        // Logged at 10,000 km with a 12,000 km interval -> due at 22,000; current 12,000 leaves
        // 10,000 km to go. Months: logged "now", 12-month interval -> due ~1 year out.
        val entries = listOf(entry(odometerKm = 10_000.0, intervalKm = 12_000.0, intervalMonths = 12))

        val due = evaluator.evaluate(entries, odometerKm = 12_000.0, nowMs = now, alreadyNotified = emptySet())

        assertTrue("comfortably-ahead entries must not fire", due.isEmpty())
    }

    @Test
    fun dueSoonButNotYetOverdueDoesNotFire() {
        // 100 km of remaining distance is "due soon" on the dashboard but NOT overdue, so it must
        // not notify: the alert is for overdue, not due-soon.
        val entries = listOf(entry(odometerKm = 10_000.0, intervalKm = 12_000.0))

        // Current odometer 21,900 -> due at 22,000 -> 100 km remaining (> 0, so not overdue).
        val due = evaluator.evaluate(entries, odometerKm = 21_900.0, nowMs = now, alreadyNotified = emptySet())

        assertTrue("due-soon (positive remaining) must not fire", due.isEmpty())
    }

    @Test
    fun newlyOverdueByKmFiresWithKmSignature() {
        val entries = listOf(entry(id = 7L, odometerKm = 10_000.0, intervalKm = 12_000.0))

        // Current 23,000 -> due at 22,000 -> overdue by 1,000 km.
        val due = evaluator.evaluate(entries, odometerKm = 23_000.0, nowMs = now, alreadyNotified = emptySet())

        assertEquals(1, due.size)
        val result = due.first()
        assertEquals(MaintenanceDueEvaluator.Interval.KM, result.tripped)
        assertEquals("7:km", result.signature)
        assertEquals("1000 km", result.overdueByText)
    }

    @Test
    fun newlyOverdueByMonthsFiresWithMonthsSignature() {
        // No distance interval; a 6-month interval logged 7 months ago is overdue by ~1 month.
        val sevenMonthsAgo = now - (7 * avgDaysPerMonth * msPerDay).toLong()
        val entries = listOf(entry(id = 3L, createdAtMs = sevenMonthsAgo, intervalMonths = 6))

        val due = evaluator.evaluate(entries, odometerKm = null, nowMs = now, alreadyNotified = emptySet())

        assertEquals(1, due.size)
        val result = due.first()
        assertEquals(MaintenanceDueEvaluator.Interval.MONTHS, result.tripped)
        assertEquals("3:months", result.signature)
        // Due at created + 6 months; overdue by ~1 month ≈ 30 days.
        assertTrue("overdue text should report days", result.overdueByText.endsWith(" days"))
    }

    @Test
    fun alreadyNotifiedCrossingDoesNotRefire() {
        val entries = listOf(entry(id = 7L, odometerKm = 10_000.0, intervalKm = 12_000.0))

        val due =
            evaluator.evaluate(
                entries,
                odometerKm = 23_000.0,
                nowMs = now,
                alreadyNotified = setOf("7:km"),
            )

        assertTrue("a crossing already in the notified set must not refire", due.isEmpty())
    }

    @Test
    fun entryWithNoIntervalNeverFires() {
        // A plain history row (no km and no months interval) shows no due line and never notifies,
        // even when the odometer has moved a long way past the logged reading.
        val entries = listOf(entry(odometerKm = 10_000.0, intervalKm = null, intervalMonths = null))

        val due = evaluator.evaluate(entries, odometerKm = 999_999.0, nowMs = now, alreadyNotified = emptySet())

        assertTrue("an interval-less entry must never fire", due.isEmpty())
    }

    @Test
    fun overdueByKmNeedsAKnownOdometer() {
        // Without a current odometer the distance check can't run, so a km-only entry stays quiet.
        val entries = listOf(entry(odometerKm = 10_000.0, intervalKm = 12_000.0))

        val due = evaluator.evaluate(entries, odometerKm = null, nowMs = now, alreadyNotified = emptySet())

        assertTrue("a null odometer must suppress the distance check", due.isEmpty())
    }

    @Test
    fun whenBothIntervalsOverdueTheLargerMagnitudeTripWins() {
        // Overdue by 5,000 km and by ~2 months: the km trip (larger magnitude) names the signature,
        // matching the dashboard's worst-first ordering.
        val threeMonthsAgo = now - (3 * avgDaysPerMonth * msPerDay).toLong()
        val entries =
            listOf(
                entry(
                    id = 9L,
                    odometerKm = 10_000.0,
                    createdAtMs = threeMonthsAgo,
                    intervalKm = 12_000.0,
                    intervalMonths = 1,
                ),
            )

        // Current 27,000 -> due at 22,000 -> overdue by 5,000 km (large). Months overdue by ~2 months
        // (~60 days), which is smaller in magnitude than 5,000.
        val due = evaluator.evaluate(entries, odometerKm = 27_000.0, nowMs = now, alreadyNotified = emptySet())

        assertEquals(1, due.size)
        assertEquals(MaintenanceDueEvaluator.Interval.KM, due.first().tripped)
        assertEquals("9:km", due.first().signature)
    }

    @Test
    fun zeroOrNegativeIntervalIsTreatedAsUnset() {
        // Matches the dashboard guard (interval must be > 0): a 0 km interval never fires.
        val entries = listOf(entry(odometerKm = 10_000.0, intervalKm = 0.0, intervalMonths = 0))

        val due = evaluator.evaluate(entries, odometerKm = 99_999.0, nowMs = now, alreadyNotified = emptySet())

        assertTrue("a non-positive interval must be treated as unset", due.isEmpty())
    }
}
