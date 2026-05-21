package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue;

import org.junit.Test;

/**
 * Decode tests for {@link ObdProtocol}. Hex responses are real captures from the test
 * vehicle (sessions 7 and 8 of the on-phone database) plus synthesised mode-22 frames
 * built from the community decode formulas. These run on the JVM with no device, so a
 * wrong PID or formula fails here instead of on a wasted car trip.
 */
public class ObdProtocolTest {

    // ---- Standard mode-01 PIDs (real captures) -------------------------------------

    @Test
    public void speedDecodesKmh() {
        assertEquals(Integer.valueOf(0), ObdProtocol.parseSpeedKph("410D00"));
        assertEquals(Integer.valueOf(65), ObdProtocol.parseSpeedKph("410D41"));
    }

    @Test
    public void speedSentinelIsRejected() {
        // 0xFF is the charge-transition sentinel, not a real 255 km/h reading.
        assertNull(ObdProtocol.parseSpeedKph("410DFF"));
        assertTrue(ObdProtocol.hasMaxSpeedSentinel("410DFF"));
        assertFalse(ObdProtocol.hasMaxSpeedSentinel("410D41"));
    }

    @Test
    public void rpmDecodesQuarterCounts() {
        // 410C1880 -> (0x18*256 + 0x80)/4 = 6272/4 = 1568 rpm (engine idling, session 7).
        assertEquals(1568f, ObdProtocol.parseRpm("410C1880"), 0.01f);
        assertEquals(0f, ObdProtocol.parseRpm("410C0000"), 0.01f);
    }

    @Test
    public void coolantDecodesWithOffset() {
        // 410563 -> 0x63 - 40 = 99 - 40 = 59 C.
        assertEquals(Integer.valueOf(59), ObdProtocol.parseCoolantC("410563"));
        assertEquals(Integer.valueOf(56), ObdProtocol.parseCoolantC("410560"));
    }

    @Test
    public void engineLoadDecodesPercent() {
        // 4104B2 -> round(0xB2 * 100 / 255) = round(178 * 100 / 255) = 70 %.
        assertEquals(Integer.valueOf(70), ObdProtocol.parseEngineLoadPct("4104B2"));
        assertEquals(Integer.valueOf(0), ObdProtocol.parseEngineLoadPct("410400"));
    }

    @Test
    public void throttleDecodesPercent() {
        // 41115D -> round(0x5D * 100 / 255) = round(93 * 100 / 255) = 36 %.
        assertEquals(Integer.valueOf(36), ObdProtocol.parseThrottlePct("41115D"));
    }

    @Test
    public void stateOfChargeDecodesPercent() {
        // 015B uses A * 100 / 255 (community sheet, mode-01 PID 5B).
        assertEquals(Integer.valueOf(0), ObdProtocol.parseStateOfChargePct("415B00"));
        assertEquals(Integer.valueOf(50), ObdProtocol.parseStateOfChargePct("415B7F"));
        assertEquals(Integer.valueOf(100), ObdProtocol.parseStateOfChargePct("415BFF"));
    }

    @Test
    public void adapterVoltageDecodes() {
        assertEquals(14.3f, ObdProtocol.parseVoltage("14.3V"), 0.001f);
        assertEquals(12.0f, ObdProtocol.parseVoltage("12.0V"), 0.001f);
    }

    // ---- Volt mode-22 PIDs (synthesised from community formulas) --------------------

    @Test
    public void hvPackVoltageDecodes() {
        // 222429 = signed (A*256+B) / 64. 0x5A00 = 23040 -> 360.0 V.
        ParsedPidValue v = ObdProtocol.parseKnownValue("222429", "6224295A00");
        assertNotNull(v);
        assertEquals("hv pack voltage", v.name);
        assertEquals(360.0, v.valueNumeric, 0.01);
        assertEquals("V", v.unit);
    }

    @Test
    public void hvPackCurrentDecodesDischargeAndCharge() {
        // 222414 = signed (A*256+B) / 20. 0x03E8 = 1000 -> +50.0 A discharging.
        ParsedPidValue discharge = ObdProtocol.parseKnownValue("222414", "62241403E8");
        assertNotNull(discharge);
        assertEquals(50.0, discharge.valueNumeric, 0.01);

        // 0xFDA8 = 64936 -> two's-complement -600 -> -30.0 A charging.
        ParsedPidValue charge = ObdProtocol.parseKnownValue("222414", "622414FDA8");
        assertNotNull(charge);
        assertEquals(-30.0, charge.valueNumeric, 0.01);
    }

    @Test
    public void hvBatteryTemperatureDecodes() {
        // 22434F = A - 40. 0x3C = 60 -> 20 C.
        ParsedPidValue v = ObdProtocol.parseKnownValue("22434F", "62434F3C");
        assertNotNull(v);
        assertEquals(20.0, v.valueNumeric, 0.01);
        assertEquals("deg C", v.unit);
    }

    @Test
    public void chargerAcInputsDecode() {
        // 224368 = A * 2. 0x78 = 120 -> 240 V.
        ParsedPidValue voltage = ObdProtocol.parseKnownValue("224368", "62436878");
        assertNotNull(voltage);
        assertEquals(240.0, voltage.valueNumeric, 0.01);

        // 224369 = A * 0.2. 0x32 = 50 -> 10.0 A.
        ParsedPidValue current = ObdProtocol.parseKnownValue("224369", "62436932");
        assertNotNull(current);
        assertEquals(10.0, current.valueNumeric, 0.01);
    }

    @Test
    public void chargerHvOutputsDecode() {
        // 22436B = signed (A*256+B) / 2. 0x030C = 780 -> 390.0 V.
        ParsedPidValue voltage = ObdProtocol.parseKnownValue("22436B", "62436B030C");
        assertNotNull(voltage);
        assertEquals(390.0, voltage.valueNumeric, 0.01);

        // 22436C = signed (A*256+B) / 20. 0x00A0 = 160 -> 8.0 A.
        ParsedPidValue current = ObdProtocol.parseKnownValue("22436C", "62436C00A0");
        assertNotNull(current);
        assertEquals(8.0, current.valueNumeric, 0.01);

        // 224373 = signed (A*256+B) W. 0x0BB8 = 3000 -> 3000 W.
        ParsedPidValue power = ObdProtocol.parseKnownValue("224373", "6243730BB8");
        assertNotNull(power);
        assertEquals(3000.0, power.valueNumeric, 0.01);
    }

    @Test
    public void lastChargeEnergyDecodes() {
        // 22437D = (A*256+B) * 10 Wh. 0x03E8 = 1000 -> 10000 Wh.
        ParsedPidValue v = ObdProtocol.parseKnownValue("22437D", "62437D03E8");
        assertNotNull(v);
        assertEquals(10000.0, v.valueNumeric, 0.01);
        assertEquals("Wh", v.unit);
    }

    @Test
    public void socThroughParseKnownValue() {
        ParsedPidValue v = ObdProtocol.parseKnownValue("015B", "415B7F");
        assertNotNull(v);
        assertEquals("state of charge", v.name);
        assertEquals(50.0, v.valueNumeric, 0.01);
        assertEquals("%", v.unit);
    }

    // ---- Error and noise handling: must return null, never throw --------------------

    @Test
    public void canErrorYieldsNull() {
        // Session 8 returned CAN ERROR for every probe; that must decode to "no value".
        assertNull(ObdProtocol.parseKnownValue("010C", "CAN ERROR"));
        assertNull(ObdProtocol.parseKnownValue("222429", "CAN ERROR"));
        assertNull(ObdProtocol.parseKnownValue("015B", "CAN ERROR"));
    }

    @Test
    public void noDataAndUnknownResponsesYieldNull() {
        assertNull(ObdProtocol.parseKnownValue("0105", "NO DATA"));
        assertNull(ObdProtocol.parseKnownValue("222414", "?"));
        assertNull(ObdProtocol.parseKnownValue("22434F", ""));
        assertNull(ObdProtocol.parseKnownValue("0104", "STOPPED"));
    }

    @Test
    public void nullInputsYieldNull() {
        assertNull(ObdProtocol.parseKnownValue("222429", null));
        assertNull(ObdProtocol.parseKnownValue(null, "6224295A00"));
        assertNull(ObdProtocol.parseKnownValue(null, null));
        assertNull(ObdProtocol.parseRpm(null));
        assertNull(ObdProtocol.parseSpeedKph(null));
        assertNull(ObdProtocol.parseStateOfChargePct(null));
    }

    @Test
    public void unknownCommandYieldsNull() {
        assertNull(ObdProtocol.parseKnownValue("ATZ", "ELM327 v1.4b"));
        assertNull(ObdProtocol.parseKnownValue("0100", "4100BE7FB813"));
    }

    @Test
    public void searchingPrefixIsToleratedBeforeRealData() {
        // The ELM prints "SEARCHING..." before the answer on a cold protocol; the real
        // 41xx frame after it must still decode.
        ParsedPidValue v = ObdProtocol.parseKnownValue("010C", "SEARCHING...\r410C1880\r>");
        assertNotNull(v);
        assertEquals(1568.0, v.valueNumeric, 0.01);
    }

    @Test
    public void lowercaseAndWhitespaceAreTolerated() {
        assertEquals(Integer.valueOf(65), ObdProtocol.parseSpeedKph("41 0d 41"));
        ParsedPidValue v = ObdProtocol.parseKnownValue("222429", "62 24 29 5a 00\r>");
        assertNotNull(v);
        assertEquals(360.0, v.valueNumeric, 0.01);
    }

    @Test
    public void summarizeStripsControlCharacters() {
        assertEquals("410C1880", ObdProtocol.summarize("410C1880\r\n>"));
        assertEquals("", ObdProtocol.summarize(null));
    }
}
