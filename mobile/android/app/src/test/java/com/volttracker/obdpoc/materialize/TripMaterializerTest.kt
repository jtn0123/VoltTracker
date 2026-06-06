package com.volttracker.obdpoc.materialize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TripMaterializer]. Uses an in-memory [MaterializerData] stub so the tests stay
 * fast and don't drag in Robolectric.
 */
class TripMaterializerTest {
    @Test
    fun emptyInputProducesEmptyList() {
        val input = MaterializerInput(1L, T_BASE, T_BASE + 10_000L)
        val data = StubData()

        val trips = TripMaterializer.materialize(input, data)

        assertEquals(0, trips.size)
    }

    @Test
    fun shortDistanceProducesNoTrip() {
        // Two samples ~50 m apart over 30 s — below the 100 m minimum.
        val data = StubData()
        data.locations.add(loc(T_BASE, 32.700000, -117.100000))
        data.locations.add(loc(T_BASE + 30 * ONE_SECOND_MS, 32.700450, -117.100000))

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(0, trips.size)
    }

    @Test
    fun continuousSegmentBecomesOneTrip() {
        // Six samples 30 s apart covering ~1 km. Should be one trip whose duration ~ 150 s.
        val data = StubData()
        val startLat = 32.700000
        for (i in 0 until 6) {
            data.locations.add(
                loc(T_BASE + i * 30 * ONE_SECOND_MS, startLat + i * 0.002, -117.100000),
            )
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(1, trips.size)
        val trip = trips[0]
        assertEquals(T_BASE, trip.startedAtMs)
        assertEquals(T_BASE + 150 * ONE_SECOND_MS, trip.endedAtMs)
        assertEquals(150 * ONE_SECOND_MS, trip.durationMs)
        assertTrue("distance should clear 1 km", trip.distanceMeters > 1000.0)
        assertEquals(6, trip.sampleCount)
        assertTrue("six samples is enough for a route", trip.hasRoute)
    }

    @Test
    fun gapOverThresholdProducesTwoTrips() {
        // Five samples, ~10 minute gap, then five more — must split.
        val data = StubData()
        val startLat = 32.700000
        for (i in 0 until 5) {
            data.locations.add(
                loc(T_BASE + i * 30 * ONE_SECOND_MS, startLat + i * 0.002, -117.100000),
            )
        }
        val resumeAtMs = T_BASE + 5 * 30 * ONE_SECOND_MS + 10 * ONE_MINUTE_MS
        for (i in 0 until 5) {
            data.locations.add(
                loc(
                    resumeAtMs + i * 30 * ONE_SECOND_MS,
                    startLat + 0.020 + i * 0.002,
                    -117.100000,
                ),
            )
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(2, trips.size)
        assertTrue(trips[0].endedAtMs < trips[1].startedAtMs)
    }

    @Test
    fun sustainedInactiveTelemetryTrimsParkedTail() {
        val data = StubData()
        val startLat = 32.700000
        for (i in 0..5) {
            val atMs = T_BASE + i * ONE_MINUTE_MS
            data.locations.add(loc(atMs, startLat + i * 0.002, -117.100000))
            data.telemetry.add(tel(atMs, 35.0))
        }
        for (i in 6..16) {
            val atMs = T_BASE + i * ONE_MINUTE_MS
            data.locations.add(loc(atMs, startLat + 5 * 0.002, -117.100000))
            data.telemetry.add(inactiveTel(atMs))
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(1, trips.size)
        val trip = trips[0]
        assertEquals(T_BASE, trip.startedAtMs)
        assertEquals(T_BASE + 5 * ONE_MINUTE_MS, trip.endedAtMs)
        assertEquals(5 * ONE_MINUTE_MS, trip.durationMs)
    }

    @Test
    fun gpsStopBoundariesProduceMultipleTrips() {
        val data = StubData()
        addMovingRun(data, T_BASE, 0, 5, 32.700000)
        addStoppedRun(data, T_BASE, 6, 12, 32.710000)
        addMovingRun(data, T_BASE, 13, 18, 32.720000)
        addStoppedRun(data, T_BASE, 19, 23, 32.730000)
        addMovingRun(data, T_BASE, 24, 29, 32.740000)
        addStoppedRun(data, T_BASE, 30, 40, 32.750000)

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(3, trips.size)
        assertEquals(T_BASE, trips[0].startedAtMs)
        assertEquals(T_BASE + 5 * ONE_MINUTE_MS, trips[0].endedAtMs)
        assertEquals(T_BASE + 13 * ONE_MINUTE_MS, trips[1].startedAtMs)
        assertEquals(T_BASE + 18 * ONE_MINUTE_MS, trips[1].endedAtMs)
        assertEquals(T_BASE + 24 * ONE_MINUTE_MS, trips[2].startedAtMs)
        assertEquals(T_BASE + 29 * ONE_MINUTE_MS, trips[2].endedAtMs)
    }

    @Test
    fun shortInactivePauseStaysInsideTrip() {
        val data = StubData()
        val startLat = 32.700000
        for (i in 0..3) {
            val atMs = T_BASE + i * ONE_MINUTE_MS
            data.locations.add(loc(atMs, startLat + i * 0.002, -117.100000))
            data.telemetry.add(tel(atMs, 35.0))
        }
        for (i in 4..6) {
            val atMs = T_BASE + i * ONE_MINUTE_MS
            data.locations.add(loc(atMs, startLat + 3 * 0.002, -117.100000))
            data.telemetry.add(inactiveTel(atMs))
        }
        for (i in 7..10) {
            val atMs = T_BASE + i * ONE_MINUTE_MS
            data.locations.add(loc(atMs, startLat + i * 0.002, -117.100000))
            data.telemetry.add(tel(atMs, 35.0))
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(1, trips.size)
        assertEquals(T_BASE + 10 * ONE_MINUTE_MS, trips[0].endedAtMs)
    }

    @Test
    fun confidenceTracksSampleCount() {
        // Six samples → WEAK (>=5 has route, <10 still WEAK).
        val few = StubData()
        for (i in 0 until 6) {
            few.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1))
        }
        // Twelve samples → OBSERVED.
        val many = StubData()
        for (i in 0 until 12) {
            many.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1))
        }

        val weak = TripMaterializer.materialize(input(few), few)[0]
        val strong = TripMaterializer.materialize(input(many), many)[0]

        assertEquals(Confidence.WEAK, weak.confidence)
        assertEquals(Confidence.OBSERVED, strong.confidence)
    }

    @Test
    fun maxSpeedComesFromTelemetryWithinWindow() {
        val data = StubData()
        for (i in 0 until 6) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1))
        }
        // Telemetry spans the same window with a spike in the middle.
        data.telemetry.add(tel(T_BASE + 10 * ONE_SECOND_MS, 35.0))
        data.telemetry.add(tel(T_BASE + 70 * ONE_SECOND_MS, 92.0))
        data.telemetry.add(tel(T_BASE + 130 * ONE_SECOND_MS, 40.0))
        // Telemetry well outside the window should NOT influence max.
        data.telemetry.add(tel(T_BASE + 10 * 60 * ONE_SECOND_MS, 250.0))

        val trip = TripMaterializer.materialize(input(data), data)[0]

        assertEquals(92.0, trip.maxSpeedKph, 0.001)
    }

    @Test
    fun energyKwhIntegratesOverWindow() {
        // Twelve location samples 30s apart so the trip materializes; the energy integral
        // is driven entirely off the telemetry's powerKw points. Constant 10 kW over 6 min
        // = 1.0 kWh.
        val data = StubData()
        for (i in 0 until 13) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1))
        }
        for (i in 0 until 13) {
            data.telemetry.add(telWithPower(T_BASE + i * 30 * ONE_SECOND_MS, 60.0, 10.0))
        }

        val trip = TripMaterializer.materialize(input(data), data)[0]

        assertNotNull(
            "energy_kwh should be populated when pack power is available",
            trip.energyKwh,
        )
        // 13 samples 30s apart = 12 intervals * 30s = 360s = 0.1 h, * 10 kW = 1.0 kWh.
        assertEquals(1.0, trip.energyKwh!!, 0.001)
    }

    @Test
    fun singleLocationSampleProducesNoTrip() {
        // A single sample can't form a segment — no distance, no duration.
        val data = StubData()
        data.locations.add(loc(T_BASE, 32.700000, -117.100000))

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(0, trips.size)
    }

    @Test
    fun allZeroSpeedStationaryFixesProduceNoTrip() {
        // Six samples covering ~150s but all at the same coordinates — zero distance,
        // below MIN_DISTANCE_METERS. Conservative behavior: drop, don't fabricate.
        val data = StubData()
        for (i in 0 until 6) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.700000, -117.100000))
        }
        // Telemetry also reports zero speed throughout.
        for (i in 0 until 6) {
            data.telemetry.add(tel(T_BASE + i * 30 * ONE_SECOND_MS, 0.0))
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(0, trips.size)
    }

    @Test
    fun allNullPowerYieldsTripWithoutEnergy() {
        // A real trip with no usable power data anywhere — energyKwh stays null
        // (we don't fabricate; an absent integral is "unknown", not zero).
        val data = StubData()
        for (i in 0 until 6) {
            data.locations.add(
                loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.700000 + i * 0.002, -117.100000),
            )
        }
        // Telemetry has no power columns populated.
        for (i in 0 until 6) {
            data.telemetry.add(tel(T_BASE + i * 30 * ONE_SECOND_MS, 40.0))
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertEquals(1, trips.size)
        assertNull(
            "energy_kwh stays null when no power samples are available",
            trips[0].energyKwh,
        )
    }

    @Test
    fun uniformlySampledRunOverCapSplitsBalancedInsteadOfPeelingPrefixes() {
        // Regression: with `if (gap > largestGap)` ties preserve splitIndex=1, so a
        // uniformly-sampled long run would recursively peel single-sample prefixes that
        // buildTrip drops (size < 2), leaking the entire body of the trip into thin air.
        // The fix (midpoint-preferring tie-break) must produce multiple cap-obeying trips.
        val data = StubData()
        val sampleInterval = 5_000L
        // 18 hours of perfectly uniform samples — every gap is exactly sampleInterval.
        val count = ((18L * 60L * 60_000L) / sampleInterval).toInt() // 12_960 samples
        val startLat = 32.700000
        for (i in 0 until count) {
            data.locations.add(
                loc(T_BASE + i * sampleInterval, startLat + i * 0.00001, -117.100000),
            )
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertTrue(
            "uniform 18h run must split into multiple trips (got ${trips.size})",
            trips.size >= 2,
        )
        for (trip in trips) {
            assertTrue(
                "trip duration ${trip.durationMs} exceeds MAX_TRIP_DURATION_MS",
                trip.durationMs <= 12L * 60L * 60_000L,
            )
        }
        // Sum of trip durations should be close to the full 18h span (within one
        // sampleInterval per split — the splitting drops the boundary edge gap, not the
        // sample). With ~2 splits and 5s intervals, the gap is < 1% of total — assert >95%.
        val totalSpan = (count - 1).toLong() * sampleInterval
        var materializedMs = 0L
        for (trip in trips) {
            materializedMs += trip.durationMs
        }
        assertTrue(
            "materialized ${materializedMs}ms of ${totalSpan}ms should cover >95% " +
                "(prevents single-sample-peel leak)",
            materializedMs * 100 > totalSpan * 95,
        )
    }

    @Test
    fun runOverMaxTripDurationSplitsAtLargestGap() {
        // Build a single continuous run that exceeds MAX_TRIP_DURATION_MS (12h).
        // Use 5-second samples (well under MAX_GAP_MS so it stays one window in the
        // first pass), with a single deliberately-larger 4-minute gap in the middle.
        val data = StubData()
        val sampleInterval = 5_000L
        // Half a day of samples on each side of the larger gap.
        val firstHalfStart = T_BASE
        val firstHalfCount = ((6L * 60L * 60_000L) / sampleInterval).toInt() // 4320 samples
        val startLat = 32.700000
        for (i in 0 until firstHalfCount) {
            data.locations.add(
                loc(firstHalfStart + i * sampleInterval, startLat + i * 0.00001, -117.100000),
            )
        }
        val largerGap = 4L * 60_000L
        val secondHalfStart =
            firstHalfStart + firstHalfCount * sampleInterval - sampleInterval + largerGap
        val secondHalfCount = ((7L * 60L * 60_000L) / sampleInterval).toInt() // 5040 samples
        val secondStartLat = startLat + firstHalfCount * 0.00001 + 0.01
        for (i in 0 until secondHalfCount) {
            data.locations.add(
                loc(
                    secondHalfStart + i * sampleInterval,
                    secondStartLat + i * 0.00001,
                    -117.100000,
                ),
            )
        }

        val trips = TripMaterializer.materialize(input(data), data)

        assertTrue(
            "13h continuous run must split into at least 2 trips at the largest gap",
            trips.size >= 2,
        )
        // Every trip must obey the cap.
        for (trip in trips) {
            assertTrue(
                "trip duration ${trip.durationMs} exceeds MAX_TRIP_DURATION_MS",
                trip.durationMs <= 12L * 60L * 60_000L,
            )
        }
    }

    @Test
    fun classificationFromAverageSpeed() {
        // ~20 kph avg → city.
        assertEquals(Trip.CLASSIFICATION_CITY, TripMaterializer.classify(20.0))
        // 50–80 kph → mixed.
        assertEquals(Trip.CLASSIFICATION_MIXED, TripMaterializer.classify(65.0))
        // ≥80 kph → highway.
        assertEquals(Trip.CLASSIFICATION_HIGHWAY, TripMaterializer.classify(95.0))
        // Zero or negative → unknown.
        assertEquals(Trip.CLASSIFICATION_UNKNOWN, TripMaterializer.classify(0.0))
    }

    // ---- helpers ------------------------------------------------------------------

    class StubData : MaterializerData {
        val locations = mutableListOf<LocationSample>()
        val telemetry = mutableListOf<TelemetrySample>()
        val pids = mutableListOf<PidObservation>()

        override fun readLocationSamples(sessionId: Long): List<LocationSample> = locations

        override fun readPidObservations(sessionId: Long): List<PidObservation> = pids

        override fun readTelemetrySamples(sessionId: Long): List<TelemetrySample> = telemetry
    }

    companion object {
        private const val T_BASE = 1_700_000_000_000L
        private const val ONE_SECOND_MS = 1_000L
        private const val ONE_MINUTE_MS = 60_000L

        private fun input(data: StubData): MaterializerInput {
            val start = if (data.locations.isEmpty()) T_BASE else data.locations[0].capturedAtMs
            val end =
                if (data.locations.isEmpty()) {
                    T_BASE
                } else {
                    data.locations[data.locations.size - 1].capturedAtMs
                }
            return MaterializerInput(1L, start, end)
        }

        private fun loc(
            atMs: Long,
            lat: Double,
            lng: Double,
        ): LocationSample = LocationSample(atMs, lat, lng, null, 5.0f)

        private fun tel(
            atMs: Long,
            speedKph: Double,
        ): TelemetrySample = TelemetrySample(atMs, speedKph, 1500, 12.4, null, null)

        private fun telWithPower(
            atMs: Long,
            speedKph: Double,
            powerKw: Double,
        ): TelemetrySample =
            // 8-arg constructor lets us provide powerKw directly so the energy integration runs.
            TelemetrySample(atMs, speedKph, 1500, 12.4, 360.0, 0.0, powerKw, null)

        private fun inactiveTel(atMs: Long): TelemetrySample = TelemetrySample(atMs, 0.0, 0, 0.0, 360.0, 0.0, 0.0, null)

        private fun addMovingRun(
            data: StubData,
            baseMs: Long,
            startMinute: Int,
            endMinute: Int,
            startLat: Double,
        ) {
            for (minute in startMinute..endMinute) {
                val atMs = baseMs + minute * ONE_MINUTE_MS
                data.locations.add(
                    loc(atMs, startLat + (minute - startMinute) * 0.002, -117.100000),
                )
                data.telemetry.add(tel(atMs, 35.0))
            }
        }

        private fun addStoppedRun(
            data: StubData,
            baseMs: Long,
            startMinute: Int,
            endMinute: Int,
            lat: Double,
        ) {
            for (minute in startMinute..endMinute) {
                val atMs = baseMs + minute * ONE_MINUTE_MS
                data.locations.add(loc(atMs, lat, -117.100000))
                data.telemetry.add(inactiveTel(atMs))
            }
        }
    }
}
