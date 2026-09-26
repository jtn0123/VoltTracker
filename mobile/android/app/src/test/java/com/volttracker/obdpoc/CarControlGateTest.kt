package com.volttracker.obdpoc

import com.volttracker.obdpoc.CarControlGate.Adapter
import com.volttracker.obdpoc.CarControlGate.Block
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarControlGateTest {
    private val gate = CarControlGate()
    private var now = 1_000_000L

    private fun sample(
        speed: Double? = 0.0,
        gear: String? = "P",
        state: String = "parked",
        speedStaleMs: Long = 0L,
        gearStaleMs: Long = 0L,
    ): JSONObject =
        JSONObject().apply {
            put("vehicleState", state)
            if (speed != null) put("speedKph", speed)
            put("speedKphStaleMs", speedStaleMs)
            if (gear != null) {
                put("prndlState", gear)
                put("prndlStateStaleMs", gearStaleMs)
            }
        }

    private fun evaluate(
        adapter: Adapter = Adapter.READY,
        lastCommandAtMs: Long = Long.MIN_VALUE / 2,
    ): Block? = gate.evaluate(now, adapter, lastCommandAtMs)

    @Test
    fun parkedStillCarWithReadyAdapterPasses() {
        gate.observe(sample(), now)
        assertNull(evaluate())
        gate.observe(sample(gear = "P"), now)
        assertNull(evaluate())
    }

    @Test
    fun adapterMustBeAnStnThatHeardTheCar() {
        gate.observe(sample(), now)
        assertEquals(Block.ADAPTER_UNKNOWN, evaluate(Adapter.UNKNOWN))
        assertEquals(Block.ADAPTER_NOT_STN, evaluate(Adapter.NOT_STN))
        assertEquals(Block.SWCAN_UNVERIFIED, evaluate(Adapter.STN_UNVERIFIED))
    }

    @Test
    fun noSampleOrOldSampleRefuses() {
        assertEquals(Block.NO_LIVE_DATA, evaluate())
        gate.observe(sample(), now)
        now += 5_001L
        assertEquals(Block.NO_LIVE_DATA, evaluate())
    }

    @Test
    fun speedMustBeKnownFreshAndZero() {
        gate.observe(sample(speed = null), now)
        assertEquals(Block.SPEED_UNKNOWN, evaluate())
        gate.observe(sample(speedStaleMs = 6_000L), now)
        assertEquals(Block.SPEED_UNKNOWN, evaluate())
        gate.observe(sample(speed = 3.0), now)
        assertEquals(Block.MOVING, evaluate())
    }

    @Test
    fun rejectedSpeedCountsAsUnknownMotion() {
        gate.observe(sample(), now)
        gate.observe(JSONObject().put("speedRejectedKph", 250.0).put("vehicleState", "parked"), now)
        assertEquals(Block.SPEED_UNKNOWN, evaluate())
    }

    @Test
    fun drivingTripRefusesEvenAtZeroSpeed() {
        gate.observe(sample(state = "driving_ev"), now)
        assertEquals(Block.DRIVING, evaluate())
        gate.observe(sample(state = "driving_gas"), now)
        assertEquals(Block.DRIVING, evaluate())
    }

    @Test
    fun gearMustBeFreshPark() {
        gate.observe(sample(gear = null), now)
        assertEquals(Block.GEAR_UNKNOWN, evaluate())
        gate.observe(sample(gear = "P", gearStaleMs = 120_001L), now)
        assertEquals(Block.GEAR_UNKNOWN, evaluate())
        gate.observe(sample(gear = "D"), now)
        assertEquals(Block.NOT_IN_PARK, evaluate())
        // An undecoded code (VoltGear's "?") and a raw code that slipped through are never Park.
        gate.observe(sample(gear = VoltGear.UNKNOWN_LETTER), now)
        assertEquals(Block.NOT_IN_PARK, evaluate())
        gate.observe(sample(gear = VoltGear.PARK_RAW.toString()), now)
        assertEquals(Block.NOT_IN_PARK, evaluate())
    }

    @Test
    fun movingAfterTheParkReadingRefusesUntilANewOne() {
        gate.observe(sample(gear = "P"), now)
        now += 1_000L
        gate.observe(sample(speed = 12.0, gear = null), now)
        now += 1_000L
        gate.observe(sample(speed = 0.0, gear = null), now)
        assertEquals(Block.MOVED_SINCE_PARK, evaluate())
        now += 1_000L
        gate.observe(sample(gear = "P"), now)
        assertNull(evaluate())
    }

    @Test
    fun rateLimitsToOneCommandPerThreeSeconds() {
        gate.observe(sample(), now)
        assertEquals(Block.RATE_LIMITED, evaluate(lastCommandAtMs = now - 2_999L))
        assertNull(evaluate(lastCommandAtMs = now - 3_000L))
    }

    @Test
    fun resetForgetsEverything() {
        gate.observe(sample(), now)
        gate.reset()
        assertEquals(Block.NO_LIVE_DATA, evaluate())
    }
}
