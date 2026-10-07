package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.ParkedDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the SW-CAN listener may treat the car as parked: Park, not just a stop at a light. */
class ParkedDetectorTest {
    private val detector = ParkedDetector(stillForMs = 60_000L, gearFreshMs = 30_000L)

    @Test
    fun aStopInDriveIsNotParked() {
        detector.observe(0.0, "D", 0L, 0L)
        detector.observe(0.0, "D", 0L, 90_000L)
        assertFalse("a long red light in Drive", detector.isParked(90_000L))
    }

    @Test
    fun parkCountsAtOnce() {
        detector.observe(0.0, "P", 0L, 0L)
        assertTrue(detector.isParked(0L))
    }

    @Test
    fun withoutAGearItTakesAMinuteStill() {
        detector.observe(0.0, "", 0L, 0L)
        assertFalse(detector.isParked(59_000L))
        assertTrue(detector.isParked(60_000L))
        detector.observe(12.0, "", 0L, 61_000L)
        assertFalse("moving again starts over", detector.isParked(61_000L))
    }

    @Test
    fun crawlingInTrafficIsNotStill() {
        detector.observe(3.0, "", 0L, 0L)
        detector.observe(3.0, "", 0L, 120_000L)
        assertFalse(detector.isParked(120_000L))
    }

    @Test
    fun parkReadAtAStandstillIsInPark() {
        assertFalse("nothing read yet", detector.isInPark())
        detector.observe(0.0, "P", 0L, 0L)
        assertTrue(detector.isInPark())
        // A quiet HS bus (no speed) and an old gear keep it: nothing shows the car moving.
        detector.observe(Double.NaN, "P", 90_000L, 90_000L)
        assertTrue(detector.isInPark())
    }

    @Test
    fun anOldParkIsNotEnough() {
        detector.observe(0.0, "P", 40_000L, 0L)
        assertFalse(detector.isInPark())
    }

    @Test
    fun anythingShowingTheCarMovingEndsPark() {
        detector.observe(0.0, "P", 0L, 0L)
        detector.observe(1.0, "", 0L, 1_000L)
        assertFalse("a fresh speed off zero", detector.isInPark())

        detector.observe(0.0, "P", 0L, 2_000L)
        detector.observe(0.0, "R", 0L, 3_000L)
        assertFalse("a fresh gear other than Park", detector.isInPark())

        detector.observe(0.0, "P", 0L, 4_000L)
        detector.moved()
        assertFalse("the body bus saw the wheels turn", detector.isInPark())
        assertFalse(detector.isParked(4_000L))

        detector.observe(0.0, "P", 0L, 5_000L)
        detector.reset()
        assertFalse(detector.isInPark())
    }

    @Test
    fun anOldGearReadingFallsBackToTheStillClock() {
        detector.observe(0.0, "D", 40_000L, 0L)
        assertTrue("a 40 s old Drive no longer vetoes a minute standing still", detector.isParked(60_000L))
        detector.observe(0.0, "D", 0L, 61_000L)
        assertFalse(detector.isParked(61_000L))
    }

    @Test
    fun noFreshSpeedIsNotStill() {
        detector.observe(Double.NaN, "P", 0L, 0L)
        assertFalse(detector.isParked(0L))
        detector.observe(0.0, "P", 0L, 1_000L)
        detector.reset()
        assertFalse(detector.isParked(1_000L))
    }
}
