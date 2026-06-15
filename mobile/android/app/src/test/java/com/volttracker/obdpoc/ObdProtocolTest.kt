package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Decode tests for [ObdProtocol]. Hex responses are real captures from the test vehicle
 * (sessions 7 and 8 of the on-phone database) plus synthesised mode-22 frames built from the
 * community decode formulas. These run on the JVM with no device, so a wrong PID or formula fails
 * here instead of on a wasted car trip.
 */
class ObdProtocolTest {
    // ---- Standard mode-01 PIDs (real captures) -------------------------------------

    @Test
    fun speedDecodesKmh() {
        assertEquals(0, ObdProtocol.parseSpeedKph("410D00"))
        assertEquals(65, ObdProtocol.parseSpeedKph("410D41"))
    }

    @Test
    fun speedSentinelIsRejected() {
        // 0xFF is the charge-transition sentinel, not a real 255 km/h reading.
        assertNull(ObdProtocol.parseSpeedKph("410DFF"))
        assertTrue(ObdProtocol.hasMaxSpeedSentinel("410DFF"))
        assertFalse(ObdProtocol.hasMaxSpeedSentinel("410D41"))
    }

    @Test
    fun rpmDecodesQuarterCounts() {
        // 410C1880 -> (0x18*256 + 0x80)/4 = 6272/4 = 1568 rpm (engine idling, session 7).
        assertEquals(1568f, ObdProtocol.parseRpm("410C1880")!!, 0.01f)
        assertEquals(0f, ObdProtocol.parseRpm("410C0000")!!, 0.01f)
    }

    @Test
    fun coolantDecodesWithOffset() {
        // 410563 -> 0x63 - 40 = 99 - 40 = 59 C.
        assertEquals(59, ObdProtocol.parseCoolantC("410563"))
        assertEquals(56, ObdProtocol.parseCoolantC("410560"))
    }

    @Test
    fun engineLoadDecodesPercent() {
        // 4104B2 -> round(0xB2 * 100 / 255) = round(178 * 100 / 255) = 70 %.
        assertEquals(70, ObdProtocol.parseEngineLoadPct("4104B2"))
        assertEquals(0, ObdProtocol.parseEngineLoadPct("410400"))
    }

    @Test
    fun throttleDecodesPercent() {
        // 41115D -> round(0x5D * 100 / 255) = round(93 * 100 / 255) = 36 %.
        assertEquals(36, ObdProtocol.parseThrottlePct("41115D"))
    }

    @Test
    fun accelPedalDecodesPercent() {
        // 0x49 (accelerator pedal position D) uses the same A * 100 / 255 formula as the
        // legacy throttle PID. 414980 -> round(0x80 * 100 / 255) = 50 %.
        assertEquals(50, ObdProtocol.parseAccelPedalPct("414980"))
        assertEquals(0, ObdProtocol.parseAccelPedalPct("414900"))
        assertEquals(100, ObdProtocol.parseAccelPedalPct("4149FF"))

        // Also reachable via the unified parseKnownValue dispatcher.
        val parsed = ObdProtocol.parseKnownValue("0149", "414980")
        assertNotNull(parsed)
        assertEquals("accelerator pedal position", parsed!!.name)
        assertEquals(50.0, parsed.valueNumeric!!, 0.01)
        assertEquals("%", parsed.unit)
    }

    @Test
    fun vinParsesFromMode09Response() {
        // ASCII "1G1ZD5ST8JF202020" — a synthetic Volt-ish VIN. Encoded as the mode-09 PID 02
        // positive-response prefix (490201) followed by 17 ASCII hex bytes.
        val hex = StringBuilder("490201")
        val vin = "1G1ZD5ST8JF202020"
        for (c in vin.toCharArray()) {
            hex.append(String.format(Locale.US, "%02X", c.code))
        }
        assertEquals(vin, ObdProtocol.parseVin(hex.toString()))
        // Tolerate the bare 4902 prefix used by adapters that strip the frame-counter byte.
        val hex2 = StringBuilder("4902")
        for (c in vin.toCharArray()) {
            hex2.append(String.format(Locale.US, "%02X", c.code))
        }
        assertEquals(vin, ObdProtocol.parseVin(hex2.toString()))
    }

    @Test
    fun vinParsesFromElmSegmentedMultiFrameResponse() {
        // With ATH0 + CAF1 (the app's actual init) the 0902 reply is multi-frame and the ELM
        // prints it in segmented form: a 3-digit hex total-length line ("014" = 20 bytes)
        // followed by "N:"-prefixed data lines. The length line and segment indices must be
        // stripped before hex decoding or every byte after them is misaligned.
        val response =
            "014\r" +
                "0: 49 02 01 31 47 31\r" +
                "1: 5A 44 35 53 54 38 4A\r" +
                "2: 46 32 30 32 30 32 30\r" +
                "\r>"
        assertEquals("1G1ZD5ST8JF202020", ObdProtocol.parseVin(response))
    }

    @Test
    fun vinRejectsInvalidCharacters() {
        // Embed an "I" (forbidden under SAE J853) in the middle — parser must reject the run.
        val hex = StringBuilder("490201")
        for (c in "1G1ZD5STIJF202020".toCharArray()) {
            hex.append(String.format(Locale.US, "%02X", c.code))
        }
        assertNull(ObdProtocol.parseVin(hex.toString()))
        assertNull(ObdProtocol.parseVin("NO SO ABCD"))
        assertNull(ObdProtocol.parseVin(null))
    }

    @Test
    fun stateOfChargeDecodesPercent() {
        // 015B uses A * 100 / 255 (community sheet, mode-01 PID 5B).
        assertEquals(0, ObdProtocol.parseStateOfChargePct("415B00"))
        assertEquals(50, ObdProtocol.parseStateOfChargePct("415B7F"))
        assertEquals(100, ObdProtocol.parseStateOfChargePct("415BFF"))
    }

    @Test
    fun adapterVoltageDecodes() {
        assertEquals(14.3f, ObdProtocol.parseVoltage("14.3V")!!, 0.001f)
        assertEquals(12.0f, ObdProtocol.parseVoltage("12.0V")!!, 0.001f)
    }

    @Test
    fun implausibleAdapterVoltageIsRejected() {
        assertNull(ObdProtocol.parseVoltage("999.0V"))
        assertNull(ObdProtocol.parseKnownValue("ATRV", "999.0V"))
    }

    @Test
    fun controlModuleVoltageDecodes() {
        val voltage = ObdProtocol.parseKnownValue("0142", "414236B0")
        assertNotNull(voltage)
        assertEquals("control module voltage", voltage!!.name)
        assertEquals(14.000, voltage.valueNumeric!!, 0.001)
        assertEquals("V", voltage.unit)
    }

    @Test
    fun standardUtilityModeOnePidsDecode() {
        val runTime = ObdProtocol.parseKnownValue("011F", "411F003C")
        assertNotNull(runTime)
        assertEquals(60.0, runTime!!.valueNumeric!!, 0.01)
        assertEquals("s", runTime.unit)

        val fuel = ObdProtocol.parseKnownValue("012F", "412F80")
        assertNotNull(fuel)
        assertEquals(50.0, fuel!!.valueNumeric!!, 0.01)

        val oilTemp = ObdProtocol.parseKnownValue("015C", "415C64")
        assertNotNull(oilTemp)
        assertEquals(60.0, oilTemp!!.valueNumeric!!, 0.01)
        assertEquals("deg C", oilTemp.unit)
    }

    // ---- Volt mode-22 PIDs (synthesised from community formulas) --------------------

    @Test
    fun hvPackVoltageDecodes() {
        // 222429 = signed (A*256+B) / 64. 0x5A00 = 23040 -> 360.0 V.
        val v = ObdProtocol.parseKnownValue("222429", "6224295A00")
        assertNotNull(v)
        assertEquals("hv pack voltage", v!!.name)
        assertEquals(360.0, v.valueNumeric!!, 0.01)
        assertEquals("V", v.unit)
    }

    @Test
    fun implausibleMode22ValuesAreRejected() {
        assertNull(ObdProtocol.parseKnownValue("222429", "6224297FFF"))
        assertNull(ObdProtocol.parseKnownValue("222414", "6224147FFF"))
        assertNull(ObdProtocol.parseKnownValue("22434F", "62434FFF"))
    }

    @Test
    fun hvPackCurrentDecodesDischargeAndCharge() {
        // 222414 = signed (A*256+B) / 20. 0x03E8 = 1000 -> +50.0 A discharging.
        val discharge = ObdProtocol.parseKnownValue("222414", "62241403E8")
        assertNotNull(discharge)
        assertEquals(50.0, discharge!!.valueNumeric!!, 0.01)

        // 0xFDA8 = 64936 -> two's-complement -600 -> -30.0 A charging.
        val charge = ObdProtocol.parseKnownValue("222414", "622414FDA8")
        assertNotNull(charge)
        assertEquals(-30.0, charge!!.valueNumeric!!, 0.01)
    }

    @Test
    fun hvBatteryTemperatureDecodes() {
        // 22434F = A - 40. 0x3C = 60 -> 20 C.
        val v = ObdProtocol.parseKnownValue("22434F", "62434F3C")
        assertNotNull(v)
        assertEquals(20.0, v!!.valueNumeric!!, 0.01)
        assertEquals("deg C", v.unit)
    }

    @Test
    fun chargerAcInputsDecode() {
        // 224368 = A * 2. 0x78 = 120 -> 240 V.
        val voltage = ObdProtocol.parseKnownValue("224368", "62436878")
        assertNotNull(voltage)
        assertEquals(240.0, voltage!!.valueNumeric!!, 0.01)

        // 224369 = A * 0.2. 0x32 = 50 -> 10.0 A.
        val current = ObdProtocol.parseKnownValue("224369", "62436932")
        assertNotNull(current)
        assertEquals(10.0, current!!.valueNumeric!!, 0.01)
    }

    @Test
    fun chargerHvOutputsDecode() {
        // 22436B = signed (A*256+B) / 2. 0x030C = 780 -> 390.0 V.
        val voltage = ObdProtocol.parseKnownValue("22436B", "62436B030C")
        assertNotNull(voltage)
        assertEquals(390.0, voltage!!.valueNumeric!!, 0.01)

        // 22436C = signed (A*256+B) / 20. 0x00A0 = 160 -> 8.0 A.
        val current = ObdProtocol.parseKnownValue("22436C", "62436C00A0")
        assertNotNull(current)
        assertEquals(8.0, current!!.valueNumeric!!, 0.01)
    }

    @Test
    fun chargerModeAndLevelDecode() {
        val notCharging = ObdProtocol.parseKnownValue("224373", "7EC 05 62 43 73 00 00")
        assertNotNull(notCharging)
        assertEquals("charging mode", notCharging!!.name)
        assertEquals("NOT_CHARGING", notCharging.valueText)
        assertEquals(0.0, notCharging.valueNumeric!!, 0.01)

        val charging = ObdProtocol.parseKnownValue("224373", "7EC05624373FFFF")
        assertNotNull(charging)
        assertEquals("CHARGING", charging!!.valueText)
        assertEquals(65535.0, charging.valueNumeric!!, 0.01)

        val level = ObdProtocol.parseKnownValue("224531", "7EC0462453102")
        assertNotNull(level)
        assertEquals("charging level", level!!.name)
        assertEquals("AC_2", level.valueText)
        assertEquals(2.0, level.valueNumeric!!, 0.01)
    }

    @Test
    fun lastChargeEnergyDecodes() {
        // 22437D = (A*256+B) * 10 Wh. 0x03E8 = 1000 -> 10000 Wh.
        val v = ObdProtocol.parseKnownValue("22437D", "62437D03E8")
        assertNotNull(v)
        assertEquals(10000.0, v!!.valueNumeric!!, 0.01)
        assertEquals("Wh", v.unit)
    }

    @Test
    fun chargeAndSocDetailPidsDecode() {
        val count = ObdProtocol.parseKnownValue("2243A5", "7EC056243A506C9")
        assertNotNull(count)
        assertEquals("hv battery charge count", count!!.name)
        assertEquals(1737.0, count.valueNumeric!!, 0.01)

        val rawSoc = ObdProtocol.parseKnownValue("2243AF", "7EC056243AF89F9")
        assertNotNull(rawSoc)
        assertEquals("hv battery raw soc", rawSoc!!.name)
        assertEquals(53.89639, rawSoc.valueNumeric!!, 0.01)

        val displaySoc = ObdProtocol.parseKnownValue("228334", "7EC0462833479")
        assertNotNull(displaySoc)
        assertEquals("hv battery displayed soc", displaySoc!!.name)
        assertEquals(47.45098, displaySoc.valueNumeric!!, 0.01)
    }

    @Test
    fun batteryCapacityAndCellHealthPidsDecode() {
        val capacity = ObdProtocol.parseKnownValue("2241A3", "6241A30205")
        assertNotNull(capacity)
        assertEquals("HV battery capacity", capacity!!.name)
        assertEquals(51.7, capacity.valueNumeric!!, 0.01)
        assertEquals("Ah", capacity.unit)

        val fallback = ObdProtocol.parseKnownValue("2245F9", "6245F91432")
        assertNotNull(fallback)
        assertEquals(51.7, fallback!!.valueNumeric!!, 0.01)

        // Gen2 Volt BECM encodes cell voltage at 1/1600 V/count, so 3.7 V = 0x1720 (5920). The old
        // Bolt-derived 5/65535 scale read this same physical cell as ~0.44 V and dropped every sample.
        val minCell = ObdProtocol.parseKnownValue("224329", "6243291720")
        assertNotNull(minCell)
        assertEquals("minimum cell voltage", minCell!!.name)
        assertEquals(3.7, minCell.valueNumeric!!, 0.001)

        val minCellNumber = ObdProtocol.parseKnownValue("22432A", "62432A20")
        assertNotNull(minCellNumber)
        assertEquals(32.0, minCellNumber!!.valueNumeric!!, 0.01)

        val maxCell = ObdProtocol.parseKnownValue("22432B", "62432B1720")
        assertNotNull(maxCell)
        assertEquals(3.7, maxCell!!.valueNumeric!!, 0.001)

        val maxCellNumber = ObdProtocol.parseKnownValue("22432C", "62432C60")
        assertNotNull(maxCellNumber)
        assertEquals(96.0, maxCellNumber!!.valueNumeric!!, 0.01)

        val socVariation = ObdProtocol.parseKnownValue("22435F", "62435F80")
        assertNotNull(socVariation)
        assertEquals(50.2, socVariation!!.valueNumeric!!, 0.1)

        val resistance = ObdProtocol.parseKnownValue("2240E9", "6240E90014")
        assertNotNull(resistance)
        assertEquals(10.0, resistance!!.valueNumeric!!, 0.01)

        val minPackVoltage = ObdProtocol.parseKnownValue("22433B", "62433B02A5")
        assertNotNull(minPackVoltage)
        assertEquals(352.0, minPackVoltage!!.valueNumeric!!, 0.1)

        assertNull("capacity sanity rejects impossible values", ObdProtocol.parseKnownValue("2241A3", "6241A3FFFF"))
        assertNull("cell-voltage sanity rejects zero", ObdProtocol.parseKnownValue("224329", "6243290000"))
    }

    @Test
    fun degradedButRealHealthValuesStillDecode() {
        // The bounds exist to reject decode garbage, not bad news: a worn pack and a faulted cell
        // are exactly what long-term health tracking must surface.
        val wornPack = ObdProtocol.parseKnownValue("2241A3", "6241A300FA") // 250 / 10 = 25.0 Ah
        assertNotNull("a heavily degraded pack capacity must not be dropped", wornPack)
        assertEquals(25.0, wornPack!!.valueNumeric!!, 0.01)

        val faultCell = ObdProtocol.parseKnownValue("224329", "6243290FA0") // 4000 / 1600 = 2.5 V
        assertNotNull("a deeply discharged cell voltage must not be dropped", faultCell)
        assertEquals(2.5, faultCell!!.valueNumeric!!, 0.01)
    }

    @Test
    fun realVoltCellVoltageFramesDecodeIntoBand() {
        // Actual frames captured from the car (2026-06-13 field logs). Under the old Bolt scale these
        // all read ~0.44 V and were dropped; the cell-balance view stayed empty. They must now decode
        // into the plausible 3.0-4.2 V band, and max - min must be a sane imbalance (tens of mV).
        val minCell = ObdProtocol.parseKnownValue("224329", "6243291687") // 5767 / 1600
        val maxCell = ObdProtocol.parseKnownValue("22432B", "62432B16A8") // 5800 / 1600
        assertNotNull("real min-cell frame must decode", minCell)
        assertNotNull("real max-cell frame must decode", maxCell)
        assertEquals(3.604, minCell!!.valueNumeric!!, 0.001)
        assertEquals(3.625, maxCell!!.valueNumeric!!, 0.001)
        val imbalanceMv = (maxCell.valueNumeric!! - minCell.valueNumeric!!) * 1000.0
        assertTrue("cell imbalance should be a sane tens-of-mV figure: $imbalanceMv", imbalanceMv in 0.0..150.0)
    }

    @Test
    fun benignSentinelResponsesAreNotTreatedAsParseFailures() {
        // All-zero Mode 22 payloads (engine torque with the engine off, a cell-number PID before the
        // BECM picks a min/max cell) and the 0xFF speed sentinel are valid "no reading" answers, not
        // malformed frames -- so they must not be flagged as pid_parse_failed.
        assertTrue(ObdProtocol.isBenignSentinelResponse("22203F", "62203F00"))
        assertTrue(ObdProtocol.isBenignSentinelResponse("22432A", "62432A00"))
        assertTrue(ObdProtocol.isBenignSentinelResponse("22432C", "62432C0000"))
        assertTrue(ObdProtocol.isBenignSentinelResponse("010D", "410DFF"))
        // A genuinely unmodeled non-zero frame is NOT benign: it stays a real parse failure.
        assertFalse(ObdProtocol.isBenignSentinelResponse("22203F", "62203F17"))
        assertFalse(ObdProtocol.isBenignSentinelResponse("010D", "410D3C"))
        assertFalse("NO DATA is not a positive sentinel", ObdProtocol.isBenignSentinelResponse("22432A", "NO DATA"))
    }

    @Test
    fun acCompressorAlternatePidsDecodeWithSpeedAndPowerOnTheRightDids() {
        // Per the Bolt BECM list (and the EnhancedPidProfiles catalog): 2282B5 = speed, 2282B7 = power.
        val speed = ObdProtocol.parseKnownValue("2282B5", "6282B50BB8")
        assertNotNull(speed)
        assertEquals("AC compressor speed", speed!!.name)
        assertEquals("rpm", speed.unit)
        assertEquals(3000.0, speed.valueNumeric!!, 0.01)

        val power = ObdProtocol.parseKnownValue("2282B7", "6282B703E8")
        assertNotNull(power)
        assertEquals("AC compressor power", power!!.name)
        assertEquals("W", power.unit)
        assertEquals(1000.0, power.valueNumeric!!, 0.01)
    }

    @Test
    fun batteryThermalExpansionPidsDecode() {
        val maxTemp = ObdProtocol.parseKnownValue("224349", "62434940")
        assertNotNull(maxTemp)
        assertEquals("HV battery max temperature", maxTemp!!.name)
        assertEquals(24.0, maxTemp.valueNumeric!!, 0.01)

        val minTemp = ObdProtocol.parseKnownValue("22434A", "62434A3F")
        assertNotNull(minTemp)
        assertEquals(23.0, minTemp!!.valueNumeric!!, 0.01)

        val maxModule = ObdProtocol.parseKnownValue("22434B", "62434B0C")
        assertNotNull(maxModule)
        assertEquals(12.0, maxModule!!.valueNumeric!!, 0.01)

        val minModule = ObdProtocol.parseKnownValue("22434C", "62434C0D")
        assertNotNull(minModule)
        assertEquals(13.0, minModule!!.valueNumeric!!, 0.01)

        val peCoolant = ObdProtocol.parseKnownValue("221C43", "621C4341")
        assertNotNull(peCoolant)
        assertEquals("power electronics coolant loop temperature", peCoolant!!.name)
        assertEquals(25.0, peCoolant.valueNumeric!!, 0.01)

        val batteryCoolant = ObdProtocol.parseKnownValue("2241A4", "6241A440")
        assertNotNull(batteryCoolant)
        assertEquals("battery coolant temperature", batteryCoolant!!.name)
        assertEquals(24.0, batteryCoolant.valueNumeric!!, 0.01)

        val minSocLimit = ObdProtocol.parseKnownValue("22433F", "62433F80")
        assertNotNull(minSocLimit)
        assertEquals(50.2, minSocLimit!!.valueNumeric!!, 0.1)
    }

    @Test
    fun auxHvacIsolationAndBrakePidsDecode() {
        val ignition = ObdProtocol.parseKnownValue("221141", "6211418C")
        assertNotNull(ignition)
        assertEquals(14.0, ignition!!.valueNumeric!!, 0.01)

        val setpoint = ObdProtocol.parseKnownValue("221C47", "621C4791")
        assertNotNull(setpoint)
        assertEquals(14.5, setpoint!!.valueNumeric!!, 0.01)

        val apmPower = ObdProtocol.parseKnownValue("2241B0", "6241B00400")
        assertNotNull(apmPower)
        assertEquals(64.0, apmPower!!.valueNumeric!!, 0.01)

        val apmCurrent = ObdProtocol.parseKnownValue("22437E", "62437E00C8")
        assertNotNull(apmCurrent)
        assertEquals(10.0, apmCurrent!!.valueNumeric!!, 0.01)

        val isolationKohm = ObdProtocol.parseKnownValue("2243A6", "6243A628")
        assertNotNull(isolationKohm)
        assertEquals(1000.0, isolationKohm!!.valueNumeric!!, 0.01)

        val isolationOhm = ObdProtocol.parseKnownValue("2241EC", "6241EC2710")
        assertNotNull(isolationOhm)
        assertEquals(10000.0, isolationOhm!!.valueNumeric!!, 0.01)

        val acCommand = ObdProtocol.parseKnownValue("2241B1", "6241B103E8")
        assertNotNull(acCommand)
        assertEquals(1000.0, acCommand!!.valueNumeric!!, 0.01)

        val cabinHeat = ObdProtocol.parseKnownValue("2241B3", "6241B303E8")
        assertNotNull(cabinHeat)
        assertEquals(1000.0, cabinHeat!!.valueNumeric!!, 0.01)

        val inverter = ObdProtocol.parseKnownValue("221C26", "621C2640")
        assertNotNull(inverter)
        assertEquals(24.0, inverter!!.valueNumeric!!, 0.01)

        val motorTemp = ObdProtocol.parseKnownValue("2228CB", "6228CB41")
        assertNotNull(motorTemp)
        assertEquals(25.0, motorTemp!!.valueNumeric!!, 0.01)

        val brakeTorque = ObdProtocol.parseKnownValue("22242C", "62242C03E8")
        assertNotNull(brakeTorque)
        assertEquals(250.0, brakeTorque!!.valueNumeric!!, 0.01)

        val regen = ObdProtocol.parseKnownValue("2224B0", "6224B008")
        assertNotNull(regen)
        assertEquals("ACTIVE", regen!!.valueText)
        assertEquals(1.0, regen.valueNumeric!!, 0.01)

        val pedal = ObdProtocol.parseKnownValue("224501", "62450104D2")
        assertNotNull(pedal)
        assertEquals(12.34, pedal!!.valueNumeric!!, 0.01)
    }

    @Test
    fun cellBecmLayoutProbePidsDecode() {
        val section = ObdProtocol.parseKnownValue("2240D7", "6240D740")
        assertNotNull(section)
        assertEquals("battery section 1 temperature", section!!.name)
        assertEquals(24.0, section.valueNumeric!!, 0.01)

        val genOneCell = ObdProtocol.parseKnownValue("224200", "624200BD6F")
        assertNotNull(genOneCell)
        assertEquals("cell 32 voltage", genOneCell!!.name)
        assertEquals(3.7, genOneCell.valueNumeric!!, 0.001)

        val boltCell = ObdProtocol.parseKnownValue("2241E0", "6241E0BD6F")
        assertNotNull(boltCell)
        assertEquals("cell 96 voltage", boltCell!!.name)
        assertEquals(3.7, boltCell.valueNumeric!!, 0.001)

        val average = ObdProtocol.parseKnownValue("22C218", "62C218BD6F")
        assertNotNull(average)
        assertEquals("average cell voltage", average!!.name)
        assertEquals(3.7, average.valueNumeric!!, 0.001)

        val current = ObdProtocol.parseKnownValue("2240D4", "6240D400C8")
        assertNotNull(current)
        assertEquals(10.0, current!!.valueNumeric!!, 0.01)
    }

    @Test
    fun evapVaporPressureDecodes() {
        val pressure = ObdProtocol.parseKnownValue("0132", "4132FF9C")
        assertNotNull(pressure)
        assertEquals("EVAP vapor pressure", pressure!!.name)
        assertEquals(-25.0, pressure.valueNumeric!!, 0.01)
        assertEquals("Pa", pressure.unit)
    }

    @Test
    fun enhancedMaintenanceAndEnginePidsDecode() {
        val oilLife = ObdProtocol.parseKnownValue("22119F", "62119FCC")
        assertNotNull(oilLife)
        assertEquals("engine oil life", oilLife!!.name)
        assertEquals(80.0, oilLife.valueNumeric!!, 0.5)
        assertEquals("%", oilLife.unit)

        val oilLifeSelector = ObdProtocol.parseKnownValue("22119F01", "62119F01CC")
        assertNotNull(oilLifeSelector)
        assertEquals("engine oil life", oilLifeSelector!!.name)
        assertEquals(80.0, oilLifeSelector.valueNumeric!!, 0.5)

        val oilLifeSelectorShortEcho =
            ObdProtocol.parseKnownValue("22119F01", "62119FCC")
        assertNotNull(oilLifeSelectorShortEcho)
        assertEquals(80.0, oilLifeSelectorShortEcho!!.valueNumeric!!, 0.5)

        val voltOilTemp = ObdProtocol.parseKnownValue("221154", "6211545A")
        assertNotNull(voltOilTemp)
        assertEquals("engine oil temperature", voltOilTemp!!.name)
        assertEquals(50.0, voltOilTemp.valueNumeric!!, 0.01)
        assertEquals("deg C", voltOilTemp.unit)

        val torque = ObdProtocol.parseKnownValue("22203F", "62203F012C")
        assertNotNull(torque)
        assertEquals("engine torque", torque!!.name)
        assertEquals(75.0, torque.valueNumeric!!, 0.01)

        val transTemp = ObdProtocol.parseKnownValue("221940", "6219405A")
        assertNotNull(transTemp)
        assertEquals("transmission temperature", transTemp!!.name)
        assertEquals(50.0, transTemp.valueNumeric!!, 0.01)

        val transTempSelector = ObdProtocol.parseKnownValue("22194001", "621940015A")
        assertNotNull(transTempSelector)
        assertEquals("transmission temperature", transTempSelector!!.name)
        assertEquals(50.0, transTempSelector.valueNumeric!!, 0.01)

        val transTempSelectorShortEcho = ObdProtocol.parseKnownValue("22194001", "6219405A")
        assertNotNull(transTempSelectorShortEcho)
        assertEquals(50.0, transTempSelectorShortEcho!!.valueNumeric!!, 0.01)
    }

    @Test
    fun motorAndCycleDistancePidsDecode() {
        val motorACurrent = ObdProtocol.parseKnownValue("222883", "62288303E8")
        assertNotNull(motorACurrent)
        assertEquals("motor A current", motorACurrent!!.name)
        assertEquals(50.0, motorACurrent.valueNumeric!!, 0.01)

        val motorBCurrent = ObdProtocol.parseKnownValue("222884", "622884FDA8")
        assertNotNull(motorBCurrent)
        assertEquals("motor B current", motorBCurrent!!.name)
        assertEquals(-30.0, motorBCurrent.valueNumeric!!, 0.01)

        val motorAVoltage = ObdProtocol.parseKnownValue("222885", "62288588B8")
        assertNotNull(motorAVoltage)
        assertEquals("motor A voltage", motorAVoltage!!.name)
        assertEquals(350.0, motorAVoltage.valueNumeric!!, 0.01)

        val motorBVoltage = ObdProtocol.parseKnownValue("222886", "6228867530")
        assertNotNull(motorBVoltage)
        assertEquals("motor B voltage", motorBVoltage!!.name)
        assertEquals(300.0, motorBVoltage.valueNumeric!!, 0.01)

        val evKm = ObdProtocol.parseKnownValue("222487", "62248704D2")
        assertNotNull(evKm)
        assertEquals("ev distance this cycle", evKm!!.name)
        assertEquals(12.34, evKm.valueNumeric!!, 0.01)
    }

    @Test
    fun batteryThermalAccessoryPidsDecode() {
        val pump = ObdProtocol.parseKnownValue("2241B2", "6241B203E8")
        assertNotNull(pump)
        assertEquals("battery coolant pump rpm", pump!!.name)
        assertEquals(1000.0, pump.valueNumeric!!, 0.01)

        val valve = ObdProtocol.parseKnownValue("2241B4", "6241B407")
        assertNotNull(valve)
        assertEquals("battery coolant valve", valve!!.name)
        assertEquals("RAW_7", valve.valueText)
        assertEquals(7.0, valve.valueNumeric!!, 0.01)

        val heater = ObdProtocol.parseKnownValue("2241B6", "6241B607D0")
        assertNotNull(heater)
        assertEquals("battery heater power", heater!!.name)
        assertEquals(2000.0, heater.valueNumeric!!, 0.01)

        // 22801E/F outside air temperature uses the GM convention A/2 - 40, covering the
        // full -40..+87.5 C span a real OAT sensor needs (the old 0.125/-5 encoding topped
        // out below 27 C). 0x96 = 150 -> 150/2 - 40 = 35.0 C, a plausible hot summer day.
        val outside = ObdProtocol.parseKnownValue("22801F", "62801F96")
        assertNotNull(outside)
        assertEquals("outside air temperature filtered", outside!!.name)
        assertEquals(35.0, outside.valueNumeric!!, 0.01)

        // 0x28 = 40 -> 40/2 - 40 = -20.0 C: sub-freezing values must be representable.
        val outsideRaw = ObdProtocol.parseKnownValue("22801E", "62801E28")
        assertNotNull(outsideRaw)
        assertEquals("outside air temperature raw", outsideRaw!!.name)
        assertEquals(-20.0, outsideRaw.valueNumeric!!, 0.01)
    }

    @Test
    fun socThroughParseKnownValue() {
        val v = ObdProtocol.parseKnownValue("015B", "415B7F")
        assertNotNull(v)
        assertEquals("state of charge", v!!.name)
        assertEquals(50.0, v.valueNumeric!!, 0.01)
        assertEquals("%", v.unit)
    }

    @Test
    fun odometerThroughParseKnownValue() {
        val v = ObdProtocol.parseKnownValue("01A6", "41A60012D687")
        assertNotNull(v)
        assertEquals("odometer", v!!.name)
        assertEquals(123456.7, v.valueNumeric!!, 0.01)
        assertEquals("km", v.unit)
    }

    // ---- parsePackPowerKw (HV pack power for the live poll) -------------------------

    @Test
    fun packPowerKwFromRealCaptures() {
        // Session 15 scan captures: 222429 -> 0x5806/64 = 352.09 V,
        // 222414 -> 0x004E/20 = 3.9 A. Power = V * A / 1000.
        val powerKw = ObdProtocol.parsePackPowerKw("6224295806", "622414004E")
        assertNotNull(powerKw)
        assertEquals(1.373, powerKw!!, 0.01)
    }

    @Test
    fun packPowerKwIsNegativeWhenCharging() {
        // Charge current decodes negative (0xFDA8 -> -30 A), so pack power follows suit.
        val powerKw = ObdProtocol.parsePackPowerKw("6224295A00", "622414FDA8")
        assertNotNull(powerKw)
        assertEquals(-10.8, powerKw!!, 0.01)
    }

    @Test
    fun packPowerKwIsNullWhenAFrameIsMissing() {
        assertNull(ObdProtocol.parsePackPowerKw("6224295806", "NO DATA"))
        assertNull(ObdProtocol.parsePackPowerKw("CAN ERROR", "622414004E"))
        assertNull(ObdProtocol.parsePackPowerKw(null, "622414004E"))
    }

    @Test
    fun batteryTemperatureFromRealCapture() {
        // Session 15 scan capture: 22434F -> 0x43(67) - 40 = 27 deg C.
        val temp = ObdProtocol.parseKnownValue("22434F", "62434F43")
        assertNotNull(temp)
        assertEquals(27.0, temp!!.valueNumeric!!, 0.001)
    }

    // ---- Error and noise handling: must return null, never throw --------------------

    @Test
    fun canErrorYieldsNull() {
        // Session 8 returned CAN ERROR for every probe; that must decode to "no value".
        assertNull(ObdProtocol.parseKnownValue("010C", "CAN ERROR"))
        assertNull(ObdProtocol.parseKnownValue("222429", "CAN ERROR"))
        assertNull(ObdProtocol.parseKnownValue("015B", "CAN ERROR"))
    }

    @Test
    fun noDataAndUnknownResponsesYieldNull() {
        assertNull(ObdProtocol.parseKnownValue("0105", "NO DATA"))
        assertNull(ObdProtocol.parseKnownValue("222414", "?"))
        assertNull(ObdProtocol.parseKnownValue("22434F", ""))
        assertNull(ObdProtocol.parseKnownValue("0104", "STOPPED"))
    }

    @Test
    fun nullInputsYieldNull() {
        assertNull(ObdProtocol.parseKnownValue("222429", null))
        assertNull(ObdProtocol.parseKnownValue(null, "6224295A00"))
        assertNull(ObdProtocol.parseKnownValue(null, null))
        assertNull(ObdProtocol.parseRpm(null))
        assertNull(ObdProtocol.parseSpeedKph(null))
        assertNull(ObdProtocol.parseStateOfChargePct(null))
    }

    @Test
    fun unknownCommandYieldsNull() {
        assertNull(ObdProtocol.parseKnownValue("ATZ", "ELM327 v1.4b"))
        assertNull(ObdProtocol.parseKnownValue("0100", "4100BE7FB813"))
    }

    @Test
    fun searchingPrefixIsToleratedBeforeRealData() {
        // The ELM prints "SEARCHING..." before the answer on a cold protocol; the real
        // 41xx frame after it must still decode.
        val v = ObdProtocol.parseKnownValue("010C", "SEARCHING...\r410C1880\r>")
        assertNotNull(v)
        assertEquals(1568.0, v!!.valueNumeric!!, 0.01)
    }

    @Test
    fun lowercaseAndWhitespaceAreTolerated() {
        assertEquals(65, ObdProtocol.parseSpeedKph("41 0d 41"))
        val v = ObdProtocol.parseKnownValue("222429", "62 24 29 5a 00\r>")
        assertNotNull(v)
        assertEquals(360.0, v!!.valueNumeric!!, 0.01)
    }

    @Test
    fun summarizeStripsControlCharacters() {
        assertEquals("410C1880", ObdProtocol.summarize("410C1880\r\n>"))
        assertEquals("", ObdProtocol.summarize(null))
    }

    @Test
    fun cleanSupportedPidsStripsElmSearchingNoise() {
        // The ELM prints "SEARCHING..." glued onto the first 4100 frame while it
        // auto-detects the protocol; only the capability frames belong in the field.
        assertEquals(
            "4100BE7FB813 410080000001",
            ObdProtocol.cleanSupportedPids("SEARCHING...4100BE7FB813\r410080000001\r>"),
        )
        assertEquals("4100BE7FB813", ObdProtocol.cleanSupportedPids("4100BE7FB813\r>"))
        assertEquals("", ObdProtocol.cleanSupportedPids("SEARCHING...\r>"))
        assertEquals("", ObdProtocol.cleanSupportedPids(null))
    }

    @Test
    fun storedDiagnosticTroubleCodesDecode() {
        // ISO 15765-4 single frame: SF PCI 06, then 43 (mode 03 positive) + DTC-count 02 +
        // two 2-byte codes. The count byte must be skipped, not decoded as DTC data.
        val codes =
            ObdProtocol.parseDiagnosticTroubleCodes("03", "7E8 06 43 02 01 33 25 A2\r>", "")

        assertEquals(2, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("stored", codes[0].status)
        assertEquals("Stored/current", codes[0].statusLabel)
        assertEquals("generic-obd", codes[0].moduleKey)
        assertEquals("ECM / powertrain (generic OBD-II)", codes[0].moduleName)
    }

    @Test
    fun dtcCountByteBoundsHowManyPairsAreRead() {
        // Count says 1 code; the trailing 25 A2 is padding/junk and must NOT become a DTC.
        val codes =
            ObdProtocol.parseDiagnosticTroubleCodes("03", "43 01 01 33 25 A2\r>", "")

        assertEquals(1, codes.size)
        assertEquals("P0133", codes[0].code)
    }

    @Test
    fun multilineDiagnosticTroubleCodesDecodeContinuationFrames() {
        // FF: PCI 10 08 + 43 + count 03 + first two codes; CF: PCI 21 + third code + padding.
        val codes =
            ObdProtocol.parseDiagnosticTroubleCodes(
                "03",
                "7E8 10 08 43 03 01 33 25 A2\r7E8 21 C0 73 00 00 00 00\r>",
                "",
            )

        assertEquals(3, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("U0073", codes[2].code)
    }

    @Test
    fun multilineDiagnosticTroubleCodesSkipNonContinuationFrames() {
        // Count promises 3 codes but the second line is a negative response (7F ...), not an
        // ISO-TP consecutive frame — it must be skipped, leaving only the FF's two codes.
        val codes =
            ObdProtocol.parseDiagnosticTroubleCodes(
                "03",
                "7E8 10 08 43 03 01 33 25 A2\r7E8 7F 03 11 00 00 00 00\r>",
                "",
            )

        assertEquals(2, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
    }

    @Test
    fun segmentedDiagnosticTroubleCodesDecodeAcrossElmSegments() {
        // ELM segmented output (ATH0 + CAF1): total-length line "00A" (10 bytes) then
        // "N:"-indexed data lines. 43 + count 04 + four codes spread over two segments.
        val codes =
            ObdProtocol.parseDiagnosticTroubleCodes(
                "03",
                "00A\r0: 43 04 01 33 25 A2\r1: C0 73 18 42 00 00 00\r\r>",
                "",
            )

        assertEquals(4, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("P25A2", codes[1].code)
        assertEquals("U0073", codes[2].code)
        assertEquals("P1842", codes[3].code)
    }

    @Test
    fun pendingAndPermanentDiagnosticStatusesDecode() {
        val pending = ObdProtocol.parseDiagnosticTroubleCodes("07", "47 01 C0 73 00 00", "7DF")
        assertEquals(1, pending.size)
        assertEquals("U0073", pending[0].code)
        assertEquals("pending", pending[0].status)

        val permanent = ObdProtocol.parseDiagnosticTroubleCodes("0A", "4A 01 25 A2 00 00", "7E0")
        assertEquals(1, permanent.size)
        assertEquals("P25A2", permanent[0].code)
        assertEquals("permanent", permanent[0].status)
        assertEquals("header-7E0", permanent[0].moduleKey)
    }

    @Test
    fun freezeFrameDiagnosticCodeDecodes() {
        // Mode 02 echoes PID and frame number: 42 02 00 then the DTC bytes. The frame-number
        // byte (00) must be skipped, not parsed as part of a code.
        val codes = ObdProtocol.parseDiagnosticTroubleCodes("0202", "42 02 00 01 33", "")

        assertEquals(1, codes.size)
        assertEquals("P0133", codes[0].code)
        assertEquals("freeze-frame", codes[0].status)
    }

    @Test
    fun zeroAndNoDataDiagnosticResponsesYieldNoCodes() {
        // "43 00" is the CAN zero-codes reply; padded variants must also stay empty.
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43 00", "").isEmpty())
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43 00 00 00 00", "").isEmpty())
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "NO DATA", "").isEmpty())
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("010C", "410C1880", "").isEmpty())
    }

    @Test
    fun truncatedFramesYieldNull() {
        // A partial response (the socket read finished before the full frame arrived)
        // must decode to "no value" rather than a wrong number or a crash.
        assertNull(ObdProtocol.parseSpeedKph("410D"))
        assertNull(ObdProtocol.parseRpm("410C18"))
        assertNull(ObdProtocol.parseCoolantC("4105"))
        assertNull(ObdProtocol.parseKnownValue("222429", "622429"))
    }

    // ---- B1: bounds-safety regression matrix ---------------------------------------
    //
    // Pin every public parser against the failure shapes weak-Bluetooth connections actually
    // produce: null, empty, 1-byte fragments, hex-prefix-only, garbage interleaved with the
    // real marker. The expectation is uniform — return null (or empty list / empty array),
    // never throw. If any future change removes a bounds check in mode01Bytes / voltByteValue
    // / voltWordValue / mode22Payload, this test fires.

    @Test
    fun mode01ParsersRejectMalformedResponses() {
        for (input in BAD_INPUTS) {
            assertNull("speed must reject: $input", ObdProtocol.parseSpeedKph(input))
            assertNull("rpm must reject: $input", ObdProtocol.parseRpm(input))
            assertNull("coolant must reject: $input", ObdProtocol.parseCoolantC(input))
            assertNull("load must reject: $input", ObdProtocol.parseEngineLoadPct(input))
            assertNull("throttle must reject: $input", ObdProtocol.parseThrottlePct(input))
            assertNull("soc must reject: $input", ObdProtocol.parseStateOfChargePct(input))
            assertFalse(
                "speed-sentinel must reject: $input",
                ObdProtocol.hasMaxSpeedSentinel(input),
            )
        }
    }

    @Test
    fun voltageParserRejectsMalformedResponses() {
        for (input in BAD_INPUTS) {
            assertNull("voltage must reject: $input", ObdProtocol.parseVoltage(input))
        }
        // Letter V present but no digits — must not throw on the empty substring.
        assertNull(ObdProtocol.parseVoltage("V"))
        assertNull(ObdProtocol.parseVoltage(">V"))
    }

    @Test
    fun mode22ParsersRejectMalformedResponses() {
        // Per-PID via parseKnownValue: every command should reject every bad input cleanly.
        val mode22Commands =
            arrayOf(
                "222429",
                "222414",
                "22434F",
                "224368",
                "224369",
                "22436B",
                "22436C",
                "224373",
                "22437D",
            )
        for (command in mode22Commands) {
            for (input in BAD_INPUTS) {
                assertNull(
                    "$command must reject: $input",
                    ObdProtocol.parseKnownValue(command, input),
                )
            }
            // Marker present but no data bytes after it.
            val markerOnly = "62" + command.substring(2)
            assertNull(
                "$command must reject marker-only: $markerOnly",
                ObdProtocol.parseKnownValue(command, markerOnly),
            )
        }
    }

    @Test
    fun packPowerHandlesMissingOrMalformedHalves() {
        // Pack power needs BOTH voltage and current — any malformed input must yield null,
        // never NaN, never a divide-by-zero, never a crash.
        assertNull(ObdProtocol.parsePackPowerKw(null, null))
        assertNull(ObdProtocol.parsePackPowerKw("", ""))
        assertNull(ObdProtocol.parsePackPowerKw("62242900FF", null))
        assertNull(ObdProtocol.parsePackPowerKw(null, "62241400FF"))
        assertNull(ObdProtocol.parsePackPowerKw("62242900FF", "garbage"))
        assertNull(ObdProtocol.parsePackPowerKw("62", "62"))
    }

    @Test
    fun diagnosticTroubleCodeParserRejectsMalformedResponses() {
        for (input in BAD_INPUTS) {
            assertTrue(
                "mode 03 must reject: $input",
                ObdProtocol.parseDiagnosticTroubleCodes("03", input, "7DF").isEmpty(),
            )
            assertTrue(
                "mode 07 must reject: $input",
                ObdProtocol.parseDiagnosticTroubleCodes("07", input, "").isEmpty(),
            )
        }
        // Marker present but no payload after — must not throw on empty substring math.
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43", "").isEmpty())
        assertTrue(ObdProtocol.parseDiagnosticTroubleCodes("03", "43 0", "").isEmpty())
    }

    // ---- B7 Mode-01 multi-PID helpers -----------------------------------------------

    @Test
    fun buildMode01MultiCommand_concatenatesPidHexAfterModeByte() {
        assertEquals(
            "010D0C49 — 3 hot-lane PIDs in a single Mode-01 round-trip",
            "010D0C49",
            ObdProtocol.buildMode01MultiCommand(listOf("0D", "0C", "49")),
        )
        assertEquals(
            "uppercases hex on the way in",
            "010D0C",
            ObdProtocol.buildMode01MultiCommand(listOf("0d", "0c")),
        )
    }

    @Test
    fun responseContainsAllMode01Pids_acceptsConcatenatedFrames() {
        // Real-shape ELM327 response to "010D0C": 41 0D 50 (speed=80 kph) + 41 0C 0B B8 (RPM=750).
        val response = "41 0D 50 41 0C 0B B8\r\r>"
        assertTrue(ObdProtocol.responseContainsAllMode01Pids(response, listOf("0D", "0C")))
    }

    @Test
    fun responseContainsAllMode01Pids_missingPidReturnsFalse() {
        // Adapter only answered with 410D; 410C marker absent → batching must fall back.
        val response = "41 0D 50\r\r>"
        assertFalse(ObdProtocol.responseContainsAllMode01Pids(response, listOf("0D", "0C")))
    }

    @Test
    fun responseContainsAllMode01Pids_emptyOrNullInputsAreFalse() {
        assertFalse(ObdProtocol.responseContainsAllMode01Pids(null, listOf("0D")))
        assertFalse(ObdProtocol.responseContainsAllMode01Pids("41 0D 50", listOf()))
    }

    @Test
    fun existingParsersHandleConcatenatedMultiPidResponse() {
        // The whole point of B7 is that the same multi-PID response can be stuffed into
        // every batched command's lastRawByCommand entry and each per-PID parser picks out
        // its own bytes via the existing indexOf("41XX") lookup. Pin that here so a refactor
        // can't silently break the rendering path.
        val response = "41 0D 50 41 0C 0B B8 41 04 80 41 11 33 41 49 7F\r\r>"
        assertEquals(80, ObdProtocol.parseSpeedKph(response))
        // 0BB8 / 4 = 750.0 RPM.
        assertEquals(750f, ObdProtocol.parseRpm(response))
        // 0x80 = 128; 128 * 100 / 255 ≈ 50%.
        assertEquals(50, ObdProtocol.parseEngineLoadPct(response))
        // 0x33 = 51; 51 * 100 / 255 = 20%.
        assertEquals(20, ObdProtocol.parseThrottlePct(response))
        // 0x7F = 127; 127 * 100 / 255 ≈ 50%.
        assertEquals(50, ObdProtocol.parseAccelPedalPct(response))
    }

    private companion object {
        private val BAD_INPUTS =
            arrayOf(
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
                "62F",
            )
    }
}
