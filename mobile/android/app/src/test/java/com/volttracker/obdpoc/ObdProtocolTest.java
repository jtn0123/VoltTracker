package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue;
import java.util.List;
import org.junit.Test;

/**
 * Decode tests for {@link ObdProtocol}. Hex responses are real captures from the test vehicle
 * (sessions 7 and 8 of the on-phone database) plus synthesised mode-22 frames built from the
 * community decode formulas. These run on the JVM with no device, so a wrong PID or formula fails
 * here instead of on a wasted car trip.
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
    public void accelPedalDecodesPercent() {
        // 0x49 (accelerator pedal position D) uses the same A * 100 / 255 formula as the
        // legacy throttle PID. 414980 -> round(0x80 * 100 / 255) = 50 %.
        assertEquals(Integer.valueOf(50), ObdProtocol.parseAccelPedalPct("414980"));
        assertEquals(Integer.valueOf(0), ObdProtocol.parseAccelPedalPct("414900"));
        assertEquals(Integer.valueOf(100), ObdProtocol.parseAccelPedalPct("4149FF"));

        // Also reachable via the unified parseKnownValue dispatcher.
        ParsedPidValue parsed = ObdProtocol.parseKnownValue("0149", "414980");
        assertNotNull(parsed);
        assertEquals("accelerator pedal position", parsed.name);
        assertEquals(50.0, parsed.valueNumeric, 0.01);
        assertEquals("%", parsed.unit);
    }

    @Test
    public void vinParsesFromMode09Response() {
        // ASCII "1G1ZD5ST8JF202020" — a synthetic Volt-ish VIN. Encoded as the mode-09 PID 02
        // positive-response prefix (490201) followed by 17 ASCII hex bytes.
        StringBuilder hex = new StringBuilder("490201");
        String vin = "1G1ZD5ST8JF202020";
        for (char c : vin.toCharArray()) {
            hex.append(String.format("%02X", (int) c));
        }
        assertEquals(vin, ObdProtocol.parseVin(hex.toString()));
        // Tolerate the bare 4902 prefix used by adapters that strip the frame-counter byte.
        StringBuilder hex2 = new StringBuilder("4902");
        for (char c : vin.toCharArray()) {
            hex2.append(String.format("%02X", (int) c));
        }
        assertEquals(vin, ObdProtocol.parseVin(hex2.toString()));
    }

    @Test
    public void vinRejectsInvalidCharacters() {
        // Embed an "I" (forbidden under SAE J853) in the middle — parser must reject the run.
        StringBuilder hex = new StringBuilder("490201");
        for (char c : "1G1ZD5STIJF202020".toCharArray()) {
            hex.append(String.format("%02X", (int) c));
        }
        assertNull(ObdProtocol.parseVin(hex.toString()));
        assertNull(ObdProtocol.parseVin("NO SO ABCD"));
        assertNull(ObdProtocol.parseVin(null));
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

    @Test
    public void controlModuleVoltageDecodes() {
        ParsedPidValue voltage = ObdProtocol.parseKnownValue("0142", "414236B0");
        assertNotNull(voltage);
        assertEquals("control module voltage", voltage.name);
        assertEquals(14.000, voltage.valueNumeric, 0.001);
        assertEquals("V", voltage.unit);
    }

    @Test
    public void standardUtilityModeOnePidsDecode() {
        ParsedPidValue runTime = ObdProtocol.parseKnownValue("011F", "411F003C");
        assertNotNull(runTime);
        assertEquals(60.0, runTime.valueNumeric, 0.01);
        assertEquals("s", runTime.unit);

        ParsedPidValue fuel = ObdProtocol.parseKnownValue("012F", "412F80");
        assertNotNull(fuel);
        assertEquals(50.0, fuel.valueNumeric, 0.01);

        ParsedPidValue oilTemp = ObdProtocol.parseKnownValue("015C", "415C64");
        assertNotNull(oilTemp);
        assertEquals(60.0, oilTemp.valueNumeric, 0.01);
        assertEquals("deg C", oilTemp.unit);
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
    }

    @Test
    public void chargerModeAndLevelDecode() {
        ParsedPidValue notCharging = ObdProtocol.parseKnownValue("224373", "7EC 05 62 43 73 00 00");
        assertNotNull(notCharging);
        assertEquals("charging mode", notCharging.name);
        assertEquals("NOT_CHARGING", notCharging.valueText);
        assertEquals(0.0, notCharging.valueNumeric, 0.01);

        ParsedPidValue charging = ObdProtocol.parseKnownValue("224373", "7EC05624373FFFF");
        assertNotNull(charging);
        assertEquals("CHARGING", charging.valueText);
        assertEquals(65535.0, charging.valueNumeric, 0.01);

        ParsedPidValue level = ObdProtocol.parseKnownValue("224531", "7EC0462453102");
        assertNotNull(level);
        assertEquals("charging level", level.name);
        assertEquals("AC_2", level.valueText);
        assertEquals(2.0, level.valueNumeric, 0.01);
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
    public void chargeAndSocDetailPidsDecode() {
        ParsedPidValue count = ObdProtocol.parseKnownValue("2243A5", "7EC056243A506C9");
        assertNotNull(count);
        assertEquals("hv battery charge count", count.name);
        assertEquals(1737.0, count.valueNumeric, 0.01);

        ParsedPidValue rawSoc = ObdProtocol.parseKnownValue("2243AF", "7EC056243AF89F9");
        assertNotNull(rawSoc);
        assertEquals("hv battery raw soc", rawSoc.name);
        assertEquals(53.89639, rawSoc.valueNumeric, 0.01);

        ParsedPidValue displaySoc = ObdProtocol.parseKnownValue("228334", "7EC0462833479");
        assertNotNull(displaySoc);
        assertEquals("hv battery displayed soc", displaySoc.name);
        assertEquals(47.45098, displaySoc.valueNumeric, 0.01);
    }

    @Test
    public void enhancedMaintenanceAndEnginePidsDecode() {
        ParsedPidValue oilLife = ObdProtocol.parseKnownValue("22119F", "62119FCC");
        assertNotNull(oilLife);
        assertEquals("engine oil life", oilLife.name);
        assertEquals(80.0, oilLife.valueNumeric, 0.5);
        assertEquals("%", oilLife.unit);

        ParsedPidValue oilLifeSelector = ObdProtocol.parseKnownValue("22119F01", "62119F01CC");
        assertNotNull(oilLifeSelector);
        assertEquals("engine oil life", oilLifeSelector.name);
        assertEquals(80.0, oilLifeSelector.valueNumeric, 0.5);

        ParsedPidValue oilLifeSelectorShortEcho =
                ObdProtocol.parseKnownValue("22119F01", "62119FCC");
        assertNotNull(oilLifeSelectorShortEcho);
        assertEquals(80.0, oilLifeSelectorShortEcho.valueNumeric, 0.5);

        ParsedPidValue voltOilTemp = ObdProtocol.parseKnownValue("221154", "6211545A");
        assertNotNull(voltOilTemp);
        assertEquals("engine oil temperature", voltOilTemp.name);
        assertEquals(50.0, voltOilTemp.valueNumeric, 0.01);
        assertEquals("deg C", voltOilTemp.unit);

        ParsedPidValue torque = ObdProtocol.parseKnownValue("22203F", "62203F012C");
        assertNotNull(torque);
        assertEquals("engine torque", torque.name);
        assertEquals(75.0, torque.valueNumeric, 0.01);

        ParsedPidValue transTemp = ObdProtocol.parseKnownValue("221940", "6219405A");
        assertNotNull(transTemp);
        assertEquals("transmission temperature", transTemp.name);
        assertEquals(50.0, transTemp.valueNumeric, 0.01);

        ParsedPidValue transTempSelector = ObdProtocol.parseKnownValue("22194001", "621940015A");
        assertNotNull(transTempSelector);
        assertEquals("transmission temperature", transTempSelector.name);
        assertEquals(50.0, transTempSelector.valueNumeric, 0.01);

        ParsedPidValue transTempSelectorShortEcho =
                ObdProtocol.parseKnownValue("22194001", "6219405A");
        assertNotNull(transTempSelectorShortEcho);
        assertEquals(50.0, transTempSelectorShortEcho.valueNumeric, 0.01);
    }

    @Test
    public void motorAndCycleDistancePidsDecode() {
        ParsedPidValue motorACurrent = ObdProtocol.parseKnownValue("222883", "62288303E8");
        assertNotNull(motorACurrent);
        assertEquals("motor A current", motorACurrent.name);
        assertEquals(50.0, motorACurrent.valueNumeric, 0.01);

        ParsedPidValue motorBCurrent = ObdProtocol.parseKnownValue("222884", "622884FDA8");
        assertNotNull(motorBCurrent);
        assertEquals("motor B current", motorBCurrent.name);
        assertEquals(-30.0, motorBCurrent.valueNumeric, 0.01);

        ParsedPidValue motorAVoltage = ObdProtocol.parseKnownValue("222885", "62288588B8");
        assertNotNull(motorAVoltage);
        assertEquals("motor A voltage", motorAVoltage.name);
        assertEquals(350.0, motorAVoltage.valueNumeric, 0.01);

        ParsedPidValue motorBVoltage = ObdProtocol.parseKnownValue("222886", "6228867530");
        assertNotNull(motorBVoltage);
        assertEquals("motor B voltage", motorBVoltage.name);
        assertEquals(300.0, motorBVoltage.valueNumeric, 0.01);

        ParsedPidValue evKm = ObdProtocol.parseKnownValue("222487", "62248704D2");
        assertNotNull(evKm);
        assertEquals("ev distance this cycle", evKm.name);
        assertEquals(12.34, evKm.valueNumeric, 0.01);
    }

    @Test
    public void batteryThermalAccessoryPidsDecode() {
        ParsedPidValue pump = ObdProtocol.parseKnownValue("2241B2", "6241B203E8");
        assertNotNull(pump);
        assertEquals("battery coolant pump rpm", pump.name);
        assertEquals(1000.0, pump.valueNumeric, 0.01);

        ParsedPidValue valve = ObdProtocol.parseKnownValue("2241B4", "6241B407");
        assertNotNull(valve);
        assertEquals("battery coolant valve", valve.name);
        assertEquals("RAW_7", valve.valueText);
        assertEquals(7.0, valve.valueNumeric, 0.01);

        ParsedPidValue heater = ObdProtocol.parseKnownValue("2241B6", "6241B607D0");
        assertNotNull(heater);
        assertEquals("battery heater power", heater.name);
        assertEquals(2000.0, heater.valueNumeric, 0.01);

        ParsedPidValue outside = ObdProtocol.parseKnownValue("22801F", "62801FC8");
        assertNotNull(outside);
        assertEquals("outside air temperature filtered", outside.name);
        assertEquals(20.0, outside.valueNumeric, 0.01);
    }

    @Test
    public void socThroughParseKnownValue() {
        ParsedPidValue v = ObdProtocol.parseKnownValue("015B", "415B7F");
        assertNotNull(v);
        assertEquals("state of charge", v.name);
        assertEquals(50.0, v.valueNumeric, 0.01);
        assertEquals("%", v.unit);
    }

    @Test
    public void odometerThroughParseKnownValue() {
        ParsedPidValue v = ObdProtocol.parseKnownValue("01A6", "41A60012D687");
        assertNotNull(v);
        assertEquals("odometer", v.name);
        assertEquals(123456.7, v.valueNumeric, 0.01);
        assertEquals("km", v.unit);
    }

    // ---- parsePackPowerKw (HV pack power for the live poll) -------------------------

    @Test
    public void packPowerKwFromRealCaptures() {
        // Session 15 scan captures: 222429 -> 0x5806/64 = 352.09 V,
        // 222414 -> 0x004E/20 = 3.9 A. Power = V * A / 1000.
        Double powerKw = ObdProtocol.parsePackPowerKw("6224295806", "622414004E");
        assertNotNull(powerKw);
        assertEquals(1.373, powerKw, 0.01);
    }

    @Test
    public void packPowerKwIsNegativeWhenCharging() {
        // Charge current decodes negative (0xFDA8 -> -30 A), so pack power follows suit.
        Double powerKw = ObdProtocol.parsePackPowerKw("6224295A00", "622414FDA8");
        assertNotNull(powerKw);
        assertEquals(-10.8, powerKw, 0.01);
    }

    @Test
    public void packPowerKwIsNullWhenAFrameIsMissing() {
        assertNull(ObdProtocol.parsePackPowerKw("6224295806", "NO DATA"));
        assertNull(ObdProtocol.parsePackPowerKw("CAN ERROR", "622414004E"));
        assertNull(ObdProtocol.parsePackPowerKw(null, "622414004E"));
    }

    @Test
    public void batteryTemperatureFromRealCapture() {
        // Session 15 scan capture: 22434F -> 0x43(67) - 40 = 27 deg C.
        ParsedPidValue temp = ObdProtocol.parseKnownValue("22434F", "62434F43");
        assertNotNull(temp);
        assertEquals(27.0, temp.valueNumeric, 0.001);
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

    @Test
    public void cleanSupportedPidsStripsElmSearchingNoise() {
        // The ELM prints "SEARCHING..." glued onto the first 4100 frame while it
        // auto-detects the protocol; only the capability frames belong in the field.
        assertEquals(
                "4100BE7FB813 410080000001",
                ObdProtocol.cleanSupportedPids("SEARCHING...4100BE7FB813\r410080000001\r>"));
        assertEquals("4100BE7FB813", ObdProtocol.cleanSupportedPids("4100BE7FB813\r>"));
        assertEquals("", ObdProtocol.cleanSupportedPids("SEARCHING...\r>"));
        assertEquals("", ObdProtocol.cleanSupportedPids(null));
    }

    @Test
    public void storedDiagnosticTroubleCodesDecode() {
        List<ObdProtocol.DiagnosticTroubleCode> codes =
                ObdProtocol.parseDiagnosticTroubleCodes("03", "7E8 06 43 01 33 25 A2 00 00\r>", "");

        assertEquals(2, codes.size());
        assertEquals("P0133", codes.get(0).code);
        assertEquals("P25A2", codes.get(1).code);
        assertEquals("stored", codes.get(0).status);
        assertEquals("Stored/current", codes.get(0).statusLabel);
        assertEquals("generic-obd", codes.get(0).moduleKey);
        assertEquals("ECM / powertrain (generic OBD-II)", codes.get(0).moduleName);
    }

    @Test
    public void multilineDiagnosticTroubleCodesDecodeContinuationFrames() {
        List<ObdProtocol.DiagnosticTroubleCode> codes =
                ObdProtocol.parseDiagnosticTroubleCodes(
                        "03", "7E8 10 08 43 01 33 25 A2\r7E8 21 C0 73 00 00 00 00\r>", "");

        assertEquals(3, codes.size());
        assertEquals("P0133", codes.get(0).code);
        assertEquals("P25A2", codes.get(1).code);
        assertEquals("U0073", codes.get(2).code);
    }

    @Test
    public void multilineDiagnosticTroubleCodesSkipNonContinuationFrames() {
        List<ObdProtocol.DiagnosticTroubleCode> codes =
                ObdProtocol.parseDiagnosticTroubleCodes(
                        "03", "7E8 10 06 43 01 33 00 00\r7E8 7F 03 11 00 00 00 00\r>", "");

        assertEquals(1, codes.size());
        assertEquals("P0133", codes.get(0).code);
    }

    @Test
    public void pendingAndPermanentDiagnosticStatusesDecode() {
        List<ObdProtocol.DiagnosticTroubleCode> pending =
                ObdProtocol.parseDiagnosticTroubleCodes("07", "47 C0 73 00 00", "7DF");
        assertEquals(1, pending.size());
        assertEquals("U0073", pending.get(0).code);
        assertEquals("pending", pending.get(0).status);

        List<ObdProtocol.DiagnosticTroubleCode> permanent =
                ObdProtocol.parseDiagnosticTroubleCodes("0A", "4A 25 A2 00 00", "7E0");
        assertEquals(1, permanent.size());
        assertEquals("P25A2", permanent.get(0).code);
        assertEquals("permanent", permanent.get(0).status);
        assertEquals("header-7E0", permanent.get(0).moduleKey);
    }

    @Test
    public void freezeFrameDiagnosticCodeDecodes() {
        List<ObdProtocol.DiagnosticTroubleCode> codes =
                ObdProtocol.parseDiagnosticTroubleCodes("0202", "42 02 01 33", "");

        assertEquals(1, codes.size());
        assertEquals("P0133", codes.get(0).code);
        assertEquals("freeze-frame", codes.get(0).status);
    }

    @Test
    public void zeroAndNoDataDiagnosticResponsesYieldNoCodes() {
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43 00 00 00 00", "").isEmpty());
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "NO DATA", "").isEmpty());
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("010C", "410C1880", "").isEmpty());
    }

    @Test
    public void truncatedFramesYieldNull() {
        // A partial response (the socket read finished before the full frame arrived)
        // must decode to "no value" rather than a wrong number or a crash.
        assertNull(ObdProtocol.parseSpeedKph("410D"));
        assertNull(ObdProtocol.parseRpm("410C18"));
        assertNull(ObdProtocol.parseCoolantC("4105"));
        assertNull(ObdProtocol.parseKnownValue("222429", "622429"));
    }

    // ---- B1: bounds-safety regression matrix ---------------------------------------
    //
    // Pin every public parser against the failure shapes weak-Bluetooth connections actually
    // produce: null, empty, 1-byte fragments, hex-prefix-only, garbage interleaved with the
    // real marker. The expectation is uniform — return null (or empty list / empty array),
    // never throw. If any future change removes a bounds check in mode01Bytes / voltByteValue
    // / voltWordValue / mode22Payload, this test fires.

    private static final String[] BAD_INPUTS =
            new String[] {
                null,
                "",
                " ",
                ">",
                "\r\n>",
                "Z",
                "ZZ",
                "?",
                "??",
                "STOPPED",
                "NO DATA",
                "SEARCHING...",
                "BUS INIT: ...ERROR",
                "41",
                "4",
                "410",
                "62",
                "622",
                "62F"
            };

    @Test
    public void mode01ParsersRejectMalformedResponses() {
        for (String input : BAD_INPUTS) {
            assertNull("speed must reject: " + input, ObdProtocol.parseSpeedKph(input));
            assertNull("rpm must reject: " + input, ObdProtocol.parseRpm(input));
            assertNull("coolant must reject: " + input, ObdProtocol.parseCoolantC(input));
            assertNull("load must reject: " + input, ObdProtocol.parseEngineLoadPct(input));
            assertNull("throttle must reject: " + input, ObdProtocol.parseThrottlePct(input));
            assertNull("soc must reject: " + input, ObdProtocol.parseStateOfChargePct(input));
            assertFalse(
                    "speed-sentinel must reject: " + input, ObdProtocol.hasMaxSpeedSentinel(input));
        }
    }

    @Test
    public void voltageParserRejectsMalformedResponses() {
        for (String input : BAD_INPUTS) {
            assertNull("voltage must reject: " + input, ObdProtocol.parseVoltage(input));
        }
        // Letter V present but no digits — must not throw on the empty substring.
        assertNull(ObdProtocol.parseVoltage("V"));
        assertNull(ObdProtocol.parseVoltage(">V"));
    }

    @Test
    public void mode22ParsersRejectMalformedResponses() {
        // Per-PID via parseKnownValue: every command should reject every bad input cleanly.
        String[] mode22Commands = {
            "222429", "222414", "22434F", "224368", "224369", "22436B", "22436C", "224373", "22437D"
        };
        for (String command : mode22Commands) {
            for (String input : BAD_INPUTS) {
                assertNull(
                        command + " must reject: " + input,
                        ObdProtocol.parseKnownValue(command, input));
            }
            // Marker present but no data bytes after it.
            String markerOnly = "62" + command.substring(2);
            assertNull(
                    command + " must reject marker-only: " + markerOnly,
                    ObdProtocol.parseKnownValue(command, markerOnly));
        }
    }

    @Test
    public void packPowerHandlesMissingOrMalformedHalves() {
        // Pack power needs BOTH voltage and current — any malformed input must yield null,
        // never NaN, never a divide-by-zero, never a crash.
        assertNull(ObdProtocol.parsePackPowerKw(null, null));
        assertNull(ObdProtocol.parsePackPowerKw("", ""));
        assertNull(ObdProtocol.parsePackPowerKw("62242900FF", null));
        assertNull(ObdProtocol.parsePackPowerKw(null, "62241400FF"));
        assertNull(ObdProtocol.parsePackPowerKw("62242900FF", "garbage"));
        assertNull(ObdProtocol.parsePackPowerKw("62", "62"));
    }

    @Test
    public void diagnosticTroubleCodeParserRejectsMalformedResponses() {
        for (String input : BAD_INPUTS) {
            assertTrue(
                    "mode 03 must reject: " + input,
                    ObdProtocol.parseDiagnosticTroubleCodes("03", input, "7DF").isEmpty());
            assertTrue(
                    "mode 07 must reject: " + input,
                    ObdProtocol.parseDiagnosticTroubleCodes("07", input, "").isEmpty());
        }
        // Marker present but no payload after — must not throw on empty substring math.
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43", "").isEmpty());
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43 0", "").isEmpty());
    }

    // ---- B7 Mode-01 multi-PID helpers -----------------------------------------------

    @Test
    public void buildMode01MultiCommand_concatenatesPidHexAfterModeByte() {
        assertEquals(
                "010D0C49 — 3 hot-lane PIDs in a single Mode-01 round-trip",
                "010D0C49",
                ObdProtocol.buildMode01MultiCommand(List.of("0D", "0C", "49")));
        assertEquals(
                "uppercases hex on the way in",
                "010D0C",
                ObdProtocol.buildMode01MultiCommand(List.of("0d", "0c")));
    }

    @Test
    public void responseContainsAllMode01Pids_acceptsConcatenatedFrames() {
        // Real-shape ELM327 response to "010D0C": 41 0D 50 (speed=80 kph) + 41 0C 0B B8 (RPM=750).
        String response = "41 0D 50 41 0C 0B B8\r\r>";
        assertTrue(ObdProtocol.responseContainsAllMode01Pids(response, List.of("0D", "0C")));
    }

    @Test
    public void responseContainsAllMode01Pids_missingPidReturnsFalse() {
        // Adapter only answered with 410D; 410C marker absent → batching must fall back.
        String response = "41 0D 50\r\r>";
        assertFalse(ObdProtocol.responseContainsAllMode01Pids(response, List.of("0D", "0C")));
    }

    @Test
    public void responseContainsAllMode01Pids_emptyOrNullInputsAreFalse() {
        assertFalse(ObdProtocol.responseContainsAllMode01Pids(null, List.of("0D")));
        assertFalse(ObdProtocol.responseContainsAllMode01Pids("41 0D 50", List.of()));
    }

    @Test
    public void existingParsersHandleConcatenatedMultiPidResponse() {
        // The whole point of B7 is that the same multi-PID response can be stuffed into
        // every batched command's lastRawByCommand entry and each per-PID parser picks out
        // its own bytes via the existing indexOf("41XX") lookup. Pin that here so a refactor
        // can't silently break the rendering path.
        String response = "41 0D 50 41 0C 0B B8 41 04 80 41 11 33 41 49 7F\r\r>";
        assertEquals(Integer.valueOf(80), ObdProtocol.parseSpeedKph(response));
        // 0BB8 / 4 = 750.0 RPM.
        assertEquals(Float.valueOf(750f), ObdProtocol.parseRpm(response));
        // 0x80 = 128; 128 * 100 / 255 ≈ 50%.
        assertEquals(Integer.valueOf(50), ObdProtocol.parseEngineLoadPct(response));
        // 0x33 = 51; 51 * 100 / 255 = 20%.
        assertEquals(Integer.valueOf(20), ObdProtocol.parseThrottlePct(response));
        // 0x7F = 127; 127 * 100 / 255 ≈ 50%.
        assertEquals(Integer.valueOf(50), ObdProtocol.parseAccelPedalPct(response));
    }
}
