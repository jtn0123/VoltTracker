package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gear-aware splitting in [TripMaterializer] ([TripSplitRules]) — and the cutover that keeps
 * trips recorded before it byte-for-byte unchanged.
 */
class TripMaterializerGearTest {
    @Test
    fun parkedWithClimateOnForTenMinutesSplitsTheTrip() {
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 25)
                .moving(25, 35)
                .data

        val trips = TripMaterializer.materialize(input(data, TripSplitRules.GEAR_AWARE), data)

        assertEquals(2, trips.size)
        assertEquals(minute(0), trips[0].startedAtMs)
        assertTrue("first trip ends when the car is put in Park", trips[0].endedAtMs <= minute(10))
        assertTrue("second trip starts after the Park stretch", trips[1].startedAtMs > minute(24))
    }

    @Test
    fun theSameDriveRecordedBeforeTheCutoverStaysOneTrip() {
        // Identical rows — gear column and all — but the session carries the legacy rules
        // version, so the materializer must produce exactly what it always did: one trip, because
        // climate draw kept the car "active" through the stop.
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 25)
                .moving(25, 35)
                .data

        val legacy = TripMaterializer.materialize(input(data, TripSplitRules.LEGACY), data)
        val legacyWithoutGear = TripMaterializer.materialize(input(data, TripSplitRules.LEGACY), data.withoutGear())

        assertEquals(1, legacy.size)
        assertEquals(minute(0), legacy[0].startedAtMs)
        assertEquals(minute(34) + HALF_MINUTE_MS, legacy[0].endedAtMs)
        assertTrue(legacy[0].parkStops.isEmpty())
        assertTripsEqual(legacyWithoutGear, legacy)
    }

    @Test
    fun gearAwareSessionWithoutGearReadingsMatchesLegacy() {
        // Unknown gear throughout (adapter never answered the PRNDL PID): legacy behavior.
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 25)
                .moving(25, 35)
                .data
                .withoutGear()

        assertTripsEqual(
            TripMaterializer.materialize(input(data, TripSplitRules.LEGACY), data),
            TripMaterializer.materialize(input(data, TripSplitRules.GEAR_AWARE), data),
        )
    }

    @Test
    fun aShortParkStopIsRecordedInsideTheTrip() {
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 15)
                .moving(15, 25)
                .data

        val trips = TripMaterializer.materialize(input(data, TripSplitRules.GEAR_AWARE), data)

        assertEquals(1, trips.size)
        val stop = trips[0].parkStops.single()
        assertEquals(minute(10), stop.startMs)
        assertEquals(minute(14) + HALF_MINUTE_MS, stop.endMs)
    }

    @Test
    fun aDoorOpeningSplitsAfterThreeMinutesInPark() {
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 14, doorAtMinute = 12)
                .moving(14, 24)
                .data

        val trips = TripMaterializer.materialize(input(data, TripSplitRules.GEAR_AWARE), data)

        assertEquals(2, trips.size)
    }

    @Test
    fun aUserSplitAtAShortParkStopCutsTheTripThere() {
        val data =
            Drive()
                .moving(0, 10)
                .parkedWithClimate(10, 15)
                .moving(15, 25)
                .data
        val stop =
            TripMaterializer
                .materialize(
                    input(data, TripSplitRules.GEAR_AWARE),
                    data,
                ).single()
                .parkStops
                .single()
        val base = input(data, TripSplitRules.GEAR_AWARE)
        val split =
            MaterializerInput(
                base.sessionId,
                base.startedAtMs,
                base.closedAtMs,
                TripSplitRules.GEAR_AWARE,
                listOf(TripSplitRules.Span(stop.startMs, stop.endMs)),
            )

        val trips = TripMaterializer.materialize(split, data)

        assertEquals(2, trips.size)
        assertTrue(trips[0].endedAtMs <= stop.startMs)
        assertTrue(trips[1].startedAtMs > stop.endMs)
        assertTrue(trips.all { it.parkStops.isEmpty() })
    }

    @Test
    fun sittingInDriveNeverSplitsEvenWhenTheCarLooksInactive() {
        // 8 minutes stationary in D with no draw at all (the legacy heuristics would call that
        // car-off dwell and split). A known Drive gear overrides them.
        val data =
            Drive()
                .moving(0, 10)
                .stillInDriveInactive(10, 18)
                .moving(18, 28)
                .data

        val legacy = TripMaterializer.materialize(input(data, TripSplitRules.LEGACY), data)
        val gearAware = TripMaterializer.materialize(input(data, TripSplitRules.GEAR_AWARE), data)

        assertEquals("legacy splits on the inactive stretch", 2, legacy.size)
        assertEquals("a known Drive gear keeps it one trip", 1, gearAware.size)
        assertTrue(gearAware[0].parkStops.isEmpty())
    }

    private fun assertTripsEqual(
        expected: List<Trip>,
        actual: List<Trip>,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].startedAtMs, actual[i].startedAtMs)
            assertEquals(expected[i].endedAtMs, actual[i].endedAtMs)
            assertEquals(expected[i].distanceMeters, actual[i].distanceMeters, 0.0)
            assertEquals(expected[i].sampleCount, actual[i].sampleCount)
            assertEquals(expected[i].energyKwh, actual[i].energyKwh)
        }
    }

    /** A 30-s cadence drive: location + telemetry rows. */
    private class Drive {
        val data = TripMaterializerTest.StubData()
        private var lat = 34.05

        fun moving(
            fromMinute: Int,
            toMinute: Int,
        ): Drive {
            each(fromMinute, toMinute) { atMs ->
                lat += 0.0015
                data.locations.add(LocationSample(atMs, lat, -118.25, null, 5.0f))
                data.telemetry.add(TelemetrySample(atMs, 45.0, 0, 14.1, 360.0, 30.0, 11.0, 60.0, DRIVE, null))
            }
            return this
        }

        /** Parked, car on, climate drawing ~2 kW (active by the legacy heuristics). */
        fun parkedWithClimate(
            fromMinute: Int,
            toMinute: Int,
            doorAtMinute: Int? = null,
        ): Drive {
            each(fromMinute, toMinute) { atMs ->
                val door = if (doorAtMinute != null && atMs == minute(doorAtMinute)) true else null
                data.locations.add(LocationSample(atMs, lat, -118.25, null, 5.0f))
                data.telemetry.add(TelemetrySample(atMs, 0.0, 0, 14.1, 360.0, 5.5, 2.0, 60.0, PARK, door))
            }
            return this
        }

        /** Stationary in Drive with no speed/RPM/12 V/power — "inactive" to the legacy rules. */
        fun stillInDriveInactive(
            fromMinute: Int,
            toMinute: Int,
        ): Drive {
            each(fromMinute, toMinute) { atMs ->
                data.locations.add(LocationSample(atMs, lat, -118.25, null, 5.0f))
                data.telemetry.add(TelemetrySample(atMs, 0.0, 0, 12.2, 360.0, 0.0, 0.0, 60.0, DRIVE, null))
            }
            return this
        }

        private fun each(
            fromMinute: Int,
            toMinute: Int,
            row: (Long) -> Unit,
        ) {
            var atMs = minute(fromMinute)
            while (atMs < minute(toMinute)) {
                row(atMs)
                atMs += HALF_MINUTE_MS
            }
        }
    }

    private companion object {
        const val PARK = 8
        const val DRIVE = 3
        const val HALF_MINUTE_MS = 30_000L
        const val BASE_MS = 1_780_000_000_000L

        fun minute(m: Int): Long = BASE_MS + m * 60_000L

        fun input(
            data: TripMaterializerTest.StubData,
            rulesVersion: Int,
        ): MaterializerInput =
            MaterializerInput(1L, data.locations.first().capturedAtMs, data.locations.last().capturedAtMs, rulesVersion)

        fun TripMaterializerTest.StubData.withoutGear(): TripMaterializerTest.StubData {
            val copy = TripMaterializerTest.StubData()
            copy.locations.addAll(locations)
            telemetry.mapTo(copy.telemetry) {
                TelemetrySample(
                    it.capturedAtMs,
                    it.speedKph,
                    it.rpm,
                    it.adapterVoltage,
                    it.packVoltage,
                    it.packCurrentA,
                    it.powerKw,
                    it.socPct,
                )
            }
            return copy
        }
    }
}
