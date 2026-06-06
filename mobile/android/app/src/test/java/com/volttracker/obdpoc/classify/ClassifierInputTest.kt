package com.volttracker.obdpoc.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [ClassifierInput]'s fail-fast bounds. Nulls always mean "unknown" and must construct
 * cleanly; physically-impossible values must throw so a parser bug or wild adapter read can't flow
 * into a confident-but-wrong classification.
 */
class ClassifierInputTest {
    @Test
    fun allNullSignalsConstructCleanly() {
        val input = ClassifierInput(null, null, null, null, null, null, SAMPLE_AT)
        assertNull(input.speedKph)
        assertNull(input.rpm)
        assertNull(input.adapterVoltage)
        assertNull(input.packCurrentA)
        assertEquals(SAMPLE_AT, input.sampleAtMs)
    }

    @Test
    fun realisticVoltReadingsConstructCleanly() {
        // Highway EV drive: 105 km/h, ICE off, 14.0V aux, 180A discharge.
        val input =
            ClassifierInput(105.0, 0, 14.0, 180.0, false, false, SAMPLE_AT)
        assertEquals(105.0, input.speedKph)
        assertEquals(180.0, input.packCurrentA)
    }

    @Test
    fun negativeSpeedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassifierInput(-1.0, null, null, null, null, null, SAMPLE_AT)
        }
    }

    @Test
    fun absurdRpmIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassifierInput(null, 99_999, null, null, null, null, SAMPLE_AT)
        }
    }

    @Test
    fun absurdVoltageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassifierInput(null, null, 250.0, null, null, null, SAMPLE_AT)
        }
    }

    @Test
    fun nanPackCurrentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassifierInput(null, null, null, Double.NaN, null, null, SAMPLE_AT)
        }
    }

    companion object {
        private const val SAMPLE_AT = 1_700_000_000_000L
    }
}
