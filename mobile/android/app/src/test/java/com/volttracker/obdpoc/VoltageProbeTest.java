package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pure JVM tests for {@link VoltageProbe#parseVolts(String)}. The decode is the only piece worth
 * unit-testing in isolation; the {@link VoltageProbe#run} path is exercised end-to-end on device.
 */
public class VoltageProbeTest {

    private static final double EPS = 1e-3;

    @Test
    public void parsesStandardResponse() {
        // 4142 31 D4 → (0x31 * 256 + 0xD4) / 1000 = (12544 + 212) / 1000 = 12.756 V
        Double v = VoltageProbe.parseVolts("41 42 31 D4\r>");
        assertNotNull(v);
        assertEquals(12.756, v, EPS);
    }

    @Test
    public void parsesResponseWithSearchingPrefix() {
        Double v = VoltageProbe.parseVolts("SEARCHING...\r41 42 31 D4\r>");
        assertNotNull(v);
        assertEquals(12.756, v, EPS);
    }

    @Test
    public void parsesLowercaseAndExtraWhitespace() {
        Double v = VoltageProbe.parseVolts("  41 42 2f 7c  \r>");
        assertNotNull(v);
        // (0x2F * 256 + 0x7C) / 1000 = (12032 + 124) / 1000 = 12.156
        assertEquals(12.156, v, EPS);
    }

    @Test
    public void rejectsNoDataResponse() {
        assertNull(VoltageProbe.parseVolts("NO DATA\r>"));
    }

    @Test
    public void rejectsNullResponse() {
        assertNull(VoltageProbe.parseVolts(null));
    }

    @Test
    public void rejectsMissingHeader() {
        // 4101 is a different PID's reply; no 4142 marker means we can't decode.
        assertNull(VoltageProbe.parseVolts("41 01 BE 1F A8 13\r>"));
    }

    @Test
    public void rejectsTruncatedPayload() {
        // 4142 with only one byte of data — not enough.
        assertNull(VoltageProbe.parseVolts("41 42 30\r>"));
    }

    @Test
    public void acceptsLowVoltageReadingForC9Hint() {
        // 4142 00 01 → 0.001 V — a totally dead battery. We deliberately accept this so the
        // dashboard's low-voltage hint (C9) fires exactly when the user most needs it; the prior
        // 4 V floor was hiding the worst-case readings.
        Double v = VoltageProbe.parseVolts("41 42 00 01\r>");
        assertNotNull(v);
        assertEquals(0.001, v, EPS);
    }

    @Test
    public void rejectsImplausiblyHighVoltage() {
        // 4142 FF FF → 65.535 V, above the 32 V ceiling.
        assertNull(VoltageProbe.parseVolts("41 42 FF FF\r>"));
    }

    @Test
    public void acceptsTypicalDeadBatteryReading() {
        // 4142 1F 40 → (0x1F * 256 + 0x40) / 1000 = (7936 + 64) / 1000 = 8.0 V — old/cold battery.
        Double v = VoltageProbe.parseVolts("41 42 1F 40\r>");
        assertNotNull(v);
        assertEquals(8.0, v, EPS);
    }
}
