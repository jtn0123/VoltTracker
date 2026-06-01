package com.volttracker.obdpoc.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Pins {@link ClassifierInput}'s fail-fast bounds. Nulls always mean "unknown" and must construct
 * cleanly; physically-impossible values must throw so a parser bug or wild adapter read can't flow
 * into a confident-but-wrong classification.
 */
public class ClassifierInputTest {

    private static final long SAMPLE_AT = 1_700_000_000_000L;

    @Test
    public void allNullSignalsConstructCleanly() {
        ClassifierInput input = new ClassifierInput(null, null, null, null, null, null, SAMPLE_AT);
        assertNull(input.speedKph);
        assertNull(input.rpm);
        assertNull(input.adapterVoltage);
        assertNull(input.packCurrentA);
        assertEquals(SAMPLE_AT, input.sampleAtMs);
    }

    @Test
    public void realisticVoltReadingsConstructCleanly() {
        // Highway EV drive: 105 km/h, ICE off, 14.0V aux, 180A discharge.
        ClassifierInput input =
                new ClassifierInput(105.0, 0, 14.0, 180.0, Boolean.FALSE, Boolean.FALSE, SAMPLE_AT);
        assertEquals(Double.valueOf(105.0), input.speedKph);
        assertEquals(Double.valueOf(180.0), input.packCurrentA);
    }

    @Test
    public void negativeSpeedIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClassifierInput(-1.0, null, null, null, null, null, SAMPLE_AT));
    }

    @Test
    public void absurdRpmIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClassifierInput(null, 99_999, null, null, null, null, SAMPLE_AT));
    }

    @Test
    public void absurdVoltageIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClassifierInput(null, null, 250.0, null, null, null, SAMPLE_AT));
    }

    @Test
    public void nanPackCurrentIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClassifierInput(null, null, null, Double.NaN, null, null, SAMPLE_AT));
    }
}
