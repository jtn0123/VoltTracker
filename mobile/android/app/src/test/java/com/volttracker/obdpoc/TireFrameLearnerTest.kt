package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TireFrameLearnerTest {
    private fun frame(
        id: Int,
        vararg bytes: Int,
    ) = SwcanFrame(id, true, bytes)

    /** Four pressures at bytes 2..5 that wander a count as the tires warm. */
    private fun tires(i: Int) = frame(TIRE_ID, 0x00, 0x11, 0x41 + i % 2, 0x42, 0x41, 0x43 + i % 2, 0x00, 0x00)

    @Test
    fun steadyPressureShapedBytesBecomeACandidate() {
        val learner = TireFrameLearner()
        learner.observe((0 until 6).map(::tires))
        val found = learner.candidates()
        assertEquals(1, found.size)
        val tire = found.single()
        assertEquals(TIRE_ID, tire.id)
        assertEquals(2, tire.offset)
        assertEquals(6, tire.frames)
        // 0x41..0x44 at 4 kPa per count: the middle of each wheel's readings.
        assertEquals(listOf(264, 264, 260, 272), tire.pressuresKpa)
        assertEquals("103D6060@2 264/264/260/272kPa n6", tire.summary())
    }

    @Test
    fun aFewFramesAreNotEnough() {
        val learner = TireFrameLearner()
        learner.observe((0 until 2).map(::tires))
        assertTrue(learner.candidates().isEmpty())
    }

    @Test
    fun countersImplausibleValuesAndMismatchedWheelsAreNotTires() {
        val learner = TireFrameLearner()
        learner.observe(
            (0 until 6).flatMap { i ->
                listOf(
                    // A rolling counter: plausible values, never steady.
                    frame(0x10306099, 0x30 + i * 4, 0x31 + i * 4, 0x32 + i * 4, 0x33 + i * 4),
                    // Steady, but 0x10 is 64 kPa: no inflated tire reads that.
                    frame(0x10304058, 0x10, 0x10, 0x10, 0x10),
                    // Steady and plausible one by one, but 0x2A against 0x50 is 15 psi apart.
                    frame(0x10308060, 0x2A, 0x40, 0x48, 0x50),
                )
            },
        )
        assertTrue(learner.candidates().isEmpty())
    }

    @Test
    fun framesTheDecoderAlreadyReadsAreLeftAlone() {
        val learner = TireFrameLearner()
        // 0x10248040 is the 12 V battery frame; these bytes would otherwise fit.
        learner.observe((0 until 6).map { frame(0x10248040, 0x00, 0x00, 0x41, 0x41, 0x41, 0x41) })
        assertTrue(learner.candidates().isEmpty())
    }

    @Test
    fun onlyTheNewestFramesCountAndResetForgetsThem() {
        val learner = TireFrameLearner(maxFramesPerId = 4)
        // Early frames that read as nothing, then steady tires: the window slides past the noise.
        learner.observe((0 until 4).map { frame(TIRE_ID, 0x00, 0x11, 0x00, 0x00, 0x00, 0x00) })
        learner.observe((0 until 4).map(::tires))
        assertEquals(4, learner.candidates().single().frames)
        learner.reset()
        assertTrue(learner.candidates().isEmpty())
    }

    private companion object {
        const val TIRE_ID = 0x103D6060
    }
}
