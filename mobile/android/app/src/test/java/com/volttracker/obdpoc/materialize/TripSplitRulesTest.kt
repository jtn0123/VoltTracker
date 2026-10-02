package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSplitRulesTest {
    private val gearAware = TripSplitRules.GEAR_AWARE

    @Test
    fun legacySessionsAreNeverAnalyzed() {
        val samples =
            Timeline()
                .gear(DRIVE, 0, 5)
                .gear(PARK, 5, 30)
                .gear(DRIVE, 30, 35)
                .samples
        assertSame(TripSplitRules.Analysis.NONE, TripSplitRules.analyze(TripSplitRules.LEGACY, samples))
        assertFalse(TripSplitRules.appliesTo(TripSplitRules.LEGACY))
        assertTrue(TripSplitRules.appliesTo(TripSplitRules.CURRENT))
    }

    @Test
    fun emptyInputIsNone() {
        assertSame(TripSplitRules.Analysis.NONE, TripSplitRules.analyze(gearAware, emptyList()))
    }

    @Test
    fun aUserSplitAtAShortStopBecomesASplitAndNoLongerAStop() {
        val samples =
            Timeline()
                .gear(DRIVE, 0, 5)
                .gear(PARK, 5, 10)
                .gear(DRIVE, 10, 15)
                .samples
        val stop = TripSplitRules.analyze(gearAware, samples).stops.single()
        val userSplit = TripSplitRules.Span(stop.startMs, stop.endMs)

        val analysis = TripSplitRules.analyze(gearAware, samples, listOf(userSplit))

        assertEquals(listOf(stop.startMs to stop.endMs), analysis.splitSpans.map { it.startMs to it.endMs })
        assertTrue("the split stop is a trip boundary now", analysis.stops.isEmpty())
    }

    @Test
    fun userSplitsAreIgnoredForLegacySessionsAndStandAloneWithoutGear() {
        val userSplit = TripSplitRules.Span(minute(5), minute(9))
        assertSame(
            TripSplitRules.Analysis.NONE,
            TripSplitRules.analyze(TripSplitRules.LEGACY, emptyList(), listOf(userSplit)),
        )
        val analysis = TripSplitRules.analyze(gearAware, emptyList(), listOf(userSplit))
        assertEquals(1, analysis.splitSpans.size)
        assertTrue(analysis.stops.isEmpty())
    }

    @Test
    fun tenMinutesInParkSplits() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5)
                    .gear(PARK, 5, 16)
                    .gear(DRIVE, 16, 20)
                    .samples,
            )
        assertEquals(1, analysis.splitSpans.size)
        assertEquals(minute(5), analysis.splitSpans[0].startMs)
        assertEquals(minute(15) + HALF_MINUTE_MS, analysis.splitSpans[0].endMs)
        assertTrue("a split Park run is not also an in-trip stop", analysis.stops.isEmpty())
    }

    @Test
    fun shortParkWithoutDoorIsAStopNotASplit() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5)
                    .gear(PARK, 5, 10)
                    .gear(DRIVE, 10, 15)
                    .samples,
            )
        assertTrue(analysis.splitSpans.isEmpty())
        assertEquals(1, analysis.stops.size)
        val stop = analysis.stops[0]
        assertEquals(minute(5), stop.startMs)
        assertEquals(minute(9) + HALF_MINUTE_MS, stop.endMs)
        assertEquals(4 * ONE_MINUTE_MS + HALF_MINUTE_MS, stop.durationMs)
        assertFalse(stop.doorOpened)
    }

    @Test
    fun threeMinutesInParkWithADoorOpenSplitsSooner() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5)
                    .gear(PARK, 5, 9, doorOpenAtMinute = 7)
                    .gear(DRIVE, 9, 12)
                    .samples,
            )
        assertEquals(1, analysis.splitSpans.size)
        assertTrue(analysis.stops.isEmpty())
    }

    @Test
    fun doorOpenWhileDrivingDoesNotAccelerate() {
        // The door reading lands while driving, not while parked: the 4-min Park stays a stop.
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5, doorOpenAtMinute = 2)
                    .gear(PARK, 5, 9)
                    .gear(DRIVE, 9, 12)
                    .samples,
            )
        assertTrue(analysis.splitSpans.isEmpty())
        assertEquals(1, analysis.stops.size)
    }

    @Test
    fun doorOpenInAParkUnderThreeMinutesDoesNotSplit() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5)
                    .gear(PARK, 5, 7, doorOpenAtMinute = 6)
                    .gear(DRIVE, 7, 10)
                    .samples,
            )
        assertTrue(analysis.splitSpans.isEmpty())
        assertTrue("under the 2-min stop floor", analysis.stops.isEmpty())
    }

    @Test
    fun doorOpenedStopIsFlagged() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(DRIVE, 0, 5)
                    .gear(PARK, 5, 8, doorOpenAtMinute = 6)
                    .gear(DRIVE, 8, 10)
                    .samples,
            )
        // 2.5 min in Park: long enough for a stop, short of the 3-min door split.
        assertTrue(analysis.splitSpans.isEmpty())
        assertTrue(analysis.stops.single().doorOpened)
    }

    @Test
    fun driveNeverSplitsHoweverLongTheCarSitsStill() {
        val timeline = Timeline().gear(DRIVE, 0, 40)
        val analysis = TripSplitRules.analyze(gearAware, timeline.samples)
        assertTrue(analysis.splitSpans.isEmpty())
        assertTrue(analysis.stops.isEmpty())
        assertTrue("a legacy span inside fresh D readings is governed", analysis.governs(minute(5), minute(30)))
        assertFalse("a span running past the last reading is not", analysis.governs(minute(5), minute(45)))
    }

    @Test
    fun otherNonParkGearsAlsoNeverSplit() {
        for (raw in listOf(7, 6, 2, 5)) {
            val analysis = TripSplitRules.analyze(gearAware, Timeline().gear(raw, 0, 30).samples)
            assertTrue("code $raw", analysis.splitSpans.isEmpty())
            assertTrue("code $raw", analysis.governs(minute(1), minute(29)))
        }
    }

    @Test
    fun staleGearFallsBackToLegacy() {
        // D readings, then 5 minutes with no gear reading at all, then D again.
        val samples =
            Timeline()
                .gear(DRIVE, 0, 10)
                .gear(null, 10, 15)
                .gear(DRIVE, 15, 20)
                .samples
        val analysis = TripSplitRules.analyze(gearAware, samples)
        assertFalse("the unknown gap is not governed", analysis.governs(minute(9), minute(16)))
        assertTrue(analysis.governs(minute(1), minute(9)))
        assertTrue(analysis.governs(minute(15), minute(19)))
    }

    @Test
    fun staleGapBreaksAParkRun() {
        // 6 min Park, 4 min with no reading, 6 min Park: two short runs, neither reaching 10 min.
        val samples =
            Timeline()
                .gear(DRIVE, 0, 3)
                .gear(PARK, 3, 9)
                .gear(null, 9, 13)
                .gear(PARK, 13, 19)
                .gear(DRIVE, 19, 22)
                .samples
        val analysis = TripSplitRules.analyze(gearAware, samples)
        assertTrue(analysis.splitSpans.isEmpty())
        assertEquals(2, analysis.stops.size)
    }

    @Test
    fun unknownCodeFallsBackToLegacy() {
        val samples =
            Timeline()
                .gear(DRIVE, 0, 5)
                .gear(UNKNOWN_CODE, 5, 20)
                .gear(DRIVE, 20, 25)
                .samples
        val analysis = TripSplitRules.analyze(gearAware, samples)
        assertTrue(analysis.splitSpans.isEmpty())
        assertFalse(analysis.governs(minute(6), minute(19)))
        assertTrue(analysis.governs(minute(0), minute(4)))
    }

    @Test
    fun parkAtTheEdgesIsNeverAStop() {
        // Sitting in Park before pulling away and after arriving (under 10 min) is not "a stop
        // inside the trip": there is no driving on both sides.
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(PARK, 0, 5)
                    .gear(DRIVE, 5, 15)
                    .gear(PARK, 15, 20)
                    .samples,
            )
        assertTrue(analysis.splitSpans.isEmpty())
        assertTrue(analysis.stops.isEmpty())
    }

    @Test
    fun parkAtTheEdgesStillSplitsOnceLongEnough() {
        val analysis =
            TripSplitRules.analyze(gearAware, Timeline().gear(DRIVE, 0, 10).gear(PARK, 10, 25).samples)
        assertEquals(1, analysis.splitSpans.size)
    }

    @Test
    fun stopsWithinFiltersToTheWindow() {
        val analysis =
            TripSplitRules.analyze(
                gearAware,
                Timeline()
                    .gear(
                        DRIVE,
                        0,
                        5,
                    ).gear(PARK, 5, 9)
                    .gear(DRIVE, 9, 20)
                    .gear(PARK, 20, 24)
                    .gear(DRIVE, 24, 30)
                    .samples,
            )
        assertEquals(2, analysis.stops.size)
        assertEquals(1, analysis.stopsWithin(minute(0), minute(15)).size)
        assertEquals(2, analysis.stopsWithin(minute(0), minute(30)).size)
        assertTrue(analysis.stopsWithin(minute(6), minute(8)).isEmpty())
    }

    /** Builds a 30-s cadence telemetry timeline, as the gear rules see it. */
    private class Timeline {
        val samples = ArrayList<TripSplitRules.GearSample>()

        fun gear(
            raw: Int?,
            fromMinute: Int,
            toMinute: Int,
            doorOpenAtMinute: Int? = null,
        ): Timeline {
            var atMs = minute(fromMinute)
            while (atMs < minute(toMinute)) {
                val door = if (doorOpenAtMinute != null && atMs == minute(doorOpenAtMinute)) true else null
                samples.add(TripSplitRules.GearSample(atMs, raw, door))
                atMs += HALF_MINUTE_MS
            }
            return this
        }
    }

    private companion object {
        const val PARK = 8
        const val DRIVE = 3
        const val UNKNOWN_CODE = 4
        const val ONE_MINUTE_MS = 60_000L
        const val HALF_MINUTE_MS = 30_000L
        const val BASE_MS = 1_780_000_000_000L

        fun minute(m: Int): Long = BASE_MS + m * ONE_MINUTE_MS
    }
}
