package com.volttracker.obdpoc.location

import com.volttracker.obdpoc.location.LocationFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises every accept/reject rule in [LocationFilter]. */
class LocationFilterTest {
    @Test
    fun firstFixIsAccepted() {
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0, "gps", T0))
    }

    @Test
    fun poorAccuracyIsRejected() {
        val f = LocationFilter()
        assertEquals(Decision.REJECT_ACCURACY, f.evaluate(LAT, LNG, 200f, T0, "gps", T0))
    }

    @Test
    fun unknownAccuracyIsNotRejectedForAccuracy() {
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, -1f, T0, "gps", T0))
    }

    @Test
    fun staleFixIsRejected() {
        val f = LocationFilter()
        // fix timestamp is 20 s older than "now"
        assertEquals(Decision.REJECT_STALE, f.evaluate(LAT, LNG, 5f, T0 - 20_000L, "gps", T0))
    }

    @Test
    fun recentFixIsAccepted() {
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0 - 2_000L, "gps", T0))
    }

    @Test
    fun impossibleJumpIsRejected() {
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0, "gps", T0))
        // ~5.5 km away one second later: ~5500 m/s
        assertEquals(
            Decision.REJECT_JUMP,
            f.evaluate(LAT + 0.05, LNG, 5f, T0 + 1_000L, "gps", T0 + 1_000L),
        )
    }

    @Test
    fun highwaySpeedIsAccepted() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        // ~30 m in one second is a plausible 30 m/s
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(LAT + 0.00027, LNG, 5f, T0 + 1_000L, "gps", T0 + 1_000L),
        )
    }

    @Test
    fun largeGapIsNotAJump() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        // 5.5 km but two minutes later: ~46 m/s, plausible
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(LAT + 0.05, LNG, 5f, T0 + 120_000L, "gps", T0 + 120_000L),
        )
    }

    @Test
    fun networkFixIsSuppressedWhileGpsIsFresh() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        assertEquals(
            Decision.REJECT_PROVIDER,
            f.evaluate(LAT, LNG, 30f, T0 + 5_000L, "network", T0 + 5_000L),
        )
    }

    @Test
    fun networkFixIsAcceptedOnceGpsIsStale() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        // 20 s later with no fresh GPS, the network fix fills the gap
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(LAT, LNG, 30f, T0 + 20_000L, "network", T0 + 20_000L),
        )
    }

    @Test
    fun firstNetworkFixIsAccepted() {
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 30f, T0, "network", T0))
    }

    @Test
    fun filterResyncsAfterConsecutiveRejects() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        // five impossible jumps in a row are all rejected
        for (i in 1..5) {
            val t = T0 + i * 1_000L
            assertEquals(Decision.REJECT_JUMP, f.evaluate(LAT + 0.05, LNG, 5f, t, "gps", t))
        }
        assertEquals(5, f.consecutiveRejects())
        // the sixth fix resyncs: the jump check is bypassed so the filter cannot wedge shut
        val t6 = T0 + 6_000L
        assertEquals(Decision.ACCEPT, f.evaluate(LAT + 0.05, LNG, 5f, t6, "gps", t6))
        assertEquals(0, f.consecutiveRejects())
    }

    @Test
    fun resyncStillEnforcesAccuracy() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        for (i in 1..5) {
            val t = T0 + i * 1_000L
            f.evaluate(LAT + 0.05, LNG, 5f, t, "gps", t)
        }
        // even while resyncing, a junk-accuracy fix is still rejected
        val t6 = T0 + 6_000L
        assertEquals(Decision.REJECT_ACCURACY, f.evaluate(LAT + 0.05, LNG, 300f, t6, "gps", t6))
    }

    @Test
    fun resetClearsCarriedOverState() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        f.reset()
        // after reset there is no reference point, so a far fix is treated as a fresh
        // first fix rather than an impossible jump from the previous session
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(LAT + 0.05, LNG, 5f, T0 + 1_000L, "gps", T0 + 1_000L),
        )
    }

    @Test
    fun rejectedFixDoesNotBecomeTheReferencePoint() {
        val f = LocationFilter()
        f.evaluate(LAT, LNG, 5f, T0, "gps", T0)
        // an outlier ~5.5 km away is rejected as a jump
        assertEquals(
            Decision.REJECT_JUMP,
            f.evaluate(LAT + 0.05, LNG, 5f, T0 + 1_000L, "gps", T0 + 1_000L),
        )
        // the next real fix near the original point is still accepted: the rejected
        // outlier did not replace the reference point
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(LAT + 0.0002, LNG, 5f, T0 + 2_000L, "gps", T0 + 2_000L),
        )
    }

    @Test
    fun fusedProviderSuppressesLaterNetworkFixes() {
        val f = LocationFilter()
        // a "fused" fix is treated as GPS-grade, not network
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0, "fused", T0))
        // so a coarse network fix shortly after is still suppressed
        assertEquals(
            Decision.REJECT_PROVIDER,
            f.evaluate(LAT, LNG, 30f, T0 + 5_000L, "network", T0 + 5_000L),
        )
    }

    @Test
    fun stationaryRereadIsAccepted() {
        // A parked car re-reporting the same coordinates a few seconds later is a valid 0 m/s fix,
        // not a jump and not a duplicate to drop — there is no minimum-movement gate.
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0, "gps", T0))
        assertEquals(Decision.ACCEPT, f.evaluate(LAT, LNG, 5f, T0 + 3_000L, "gps", T0 + 3_000L))
    }

    @Test
    fun antimeridianCrossingIsNotAJump() {
        // Crossing the 180° meridian (lng 179.999 -> -179.999) is ~222 m, not ~24,000 mi: the
        // haversine wrap is handled by sin(Δlng/2)'s periodicity, so a real crossing at ~22 m/s is
        // accepted rather than rejected as a teleport. (A naive equirectangular Δlng would
        // teleport.)
        val f = LocationFilter()
        assertEquals(Decision.ACCEPT, f.evaluate(1.0, 179.999, 5f, T0, "gps", T0))
        assertEquals(
            Decision.ACCEPT,
            f.evaluate(1.0, -179.999, 5f, T0 + 10_000L, "gps", T0 + 10_000L),
        )
    }

    @Test
    fun haversineHandlesAntimeridianWrap() {
        // Direct guard on the formula: two points 0.002° apart straddling the antimeridian are a
        // couple hundred metres apart, well under a kilometre — never the whole-globe distance.
        val meters = LocationFilter.haversineMeters(1.0, 179.999, 1.0, -179.999)
        assertEquals(222.0, meters, 30.0)
    }

    companion object {
        private const val LAT = 32.7157
        private const val LNG = -117.1611
        private const val T0 = 1_700_000_000_000L
    }
}
