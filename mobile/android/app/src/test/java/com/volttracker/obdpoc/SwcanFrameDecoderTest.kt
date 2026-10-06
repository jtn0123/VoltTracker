package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SW-CAN decoders to their layouts (GM's low-speed DBC where it covers a frame, else the
 * OVMS vehicle_voltampera math). Frames built by hand from a decoder's bit layout prove only the
 * port; the ones marked with a date are the car's own bytes from that capture.
 */
class SwcanFrameDecoderTest {
    private fun frame(
        id: Int,
        vararg bytes: Int,
    ) = SwcanFrame(id, true, bytes)

    private fun decode(
        id: Int,
        vararg bytes: Int,
    ): Map<SwcanField, Any> = SwcanFrameDecoder.decode(frame(id, *bytes)).associate { it.field to it.value }

    // ---- monitor output parsing ------------------------------------------------------------

    @Test
    fun parsesSpacedTwentyNineBitLine() {
        val f = SwcanFrameDecoder.parseLine("10 24 80 40 00 00 60 D9 00 FC 00 00")!!
        assertTrue(f.extended)
        assertEquals(0x10248040, f.id)
        assertEquals(8, f.dlc)
        assertEquals(0xFC, f.data[5])
        assertEquals(0x124, f.gmlanPid)
    }

    @Test
    fun parsesSpacedElevenBitLineAndDlcDigit() {
        val f = SwcanFrameDecoder.parseLine("621 00 40 00")!!
        assertFalse(f.extended)
        assertEquals(0x621, f.id)
        assertEquals(3, f.dlc)
        val withDlc = SwcanFrameDecoder.parseLine("10 24 80 40 8 00 00 60 D9 00 FC 00 00")!!
        assertEquals(8, withDlc.dlc)
        assertEquals(0x60, withDlc.data[2])
        val eightCharId = SwcanFrameDecoder.parseLine("10248040 00 00 60")!!
        assertEquals(0x10248040, eightCharId.id)
        assertEquals(3, eightCharId.dlc)
    }

    @Test
    fun parsesCompactLinesByLengthParity() {
        val extended = SwcanFrameDecoder.parseLine("102480400000600D")!!
        assertTrue(extended.extended)
        assertEquals(0x10248040, extended.id)
        assertEquals(4, extended.dlc)
        val standard = SwcanFrameDecoder.parseLine("6210040")!!
        assertFalse(standard.extended)
        assertEquals(0x621, standard.id)
        assertEquals(2, standard.dlc)
        assertNull(SwcanFrameDecoder.parseLine("102480"))
    }

    @Test
    fun rejectsStatusTextAndMalformedLines() {
        for (line in listOf(
            "",
            "STOPPED",
            "BUFFER FULL",
            "CAN ERROR",
            "OK",
            "?",
            "10 24 8",
            "10 24 80 40 1 2 3",
            "FFFFFFFFF0",
        )) {
            assertNull("'$line' must not parse", SwcanFrameDecoder.parseLine(line))
        }
        // more than 8 data bytes
        assertNull(SwcanFrameDecoder.parseLine("10 24 80 40 00 00 00 00 00 00 00 00 00"))
        // id above 29 bits
        assertNull(SwcanFrameDecoder.parseLine("FF FF FF FF 00"))
        assertNull(SwcanFrameDecoder.parseLine("1"))
    }

    @Test
    fun splitsFullMonitorTranscript() {
        val raw = "10 24 80 40 00 00 60 D9 00 FC 00 00\r621 00\rBUFFER FULL\r\nSTOPPED\r\r>"
        val frames = SwcanFrameDecoder.parseMonitorOutput(raw)
        assertEquals(2, frames.size)
        assertTrue(SwcanFrameDecoder.parseMonitorOutput(null).isEmpty())
        val decoded = SwcanFrameDecoder.decodeAll(frames)
        assertEquals(3, decoded.size)
    }

    // ---- exact-id frames -------------------------------------------------------------------

    @Test
    fun battery12v() {
        val v = decode(SwcanFrameDecoder.ID_BATTERY_12V, 0, 0, 0x60, 0xD9, 0, 0xFC)
        assertEquals(12.6, v[SwcanField.AUX12V_VOLTAGE])
        assertEquals(85.0, v[SwcanField.AUX12V_SOC])
        assertEquals(-2.0, v[SwcanField.AUX12V_CURRENT])
        assertEquals(6.5, decode(SwcanFrameDecoder.ID_BATTERY_12V, 0, 0, 0, 0, 0, 0x0D)[SwcanField.AUX12V_CURRENT])
        assertTrue(decode(SwcanFrameDecoder.ID_BATTERY_12V, 0, 0, 0x60).isEmpty())
    }

    @Test
    fun battery12vDropsTheNotAvailableSocByte() {
        // Captured on a 2017 Volt (2026-09-29): BatSOC is 0xFF in every frame, which must not read
        // as a steady 100 %. Voltage and current still decode.
        val f = SwcanFrameDecoder.parseLine("10 24 80 40 00 00 61 FF FF 00 00")!!
        val v = SwcanFrameDecoder.decode(f).associate { it.field to it.value }
        assertEquals(12.7, v[SwcanField.AUX12V_VOLTAGE])
        assertNull(v[SwcanField.AUX12V_SOC])
        assertEquals(0.0, v[SwcanField.AUX12V_CURRENT])
    }

    @Test
    fun tirePressuresDropMissingSensors() {
        val v = decode(SwcanFrameDecoder.ID_TPMS, 0, 0, 0x41, 0x40, 0x42, 0xFF)
        assertEquals(260.0, v[SwcanField.TIRE_FL])
        assertEquals(256.0, v[SwcanField.TIRE_RL])
        assertEquals(264.0, v[SwcanField.TIRE_FR])
        assertFalse(v.containsKey(SwcanField.TIRE_RR))
        assertFalse(decode(SwcanFrameDecoder.ID_TPMS, 0, 0, 0, 0x40, 0x40, 0x40).containsKey(SwcanField.TIRE_FL))
        assertTrue(decode(SwcanFrameDecoder.ID_TPMS, 0, 0, 0x40).isEmpty())
    }

    @Test
    fun doorLockCommand() {
        val locked = decode(SwcanFrameDecoder.ID_DOOR_LOCK, 0, 0x05, 0, 0x05)
        assertEquals("locked", locked[SwcanField.LOCK_STATE])
        assertEquals("fob", locked[SwcanField.LOCK_SOURCE])
        val sources = mapOf(0x01 to "panel", 0x06 to "keyless", 0x07 to "OnStar", 0x0A to "auto", 0x33 to "unknown")
        for ((code, name) in sources) {
            val v = decode(SwcanFrameDecoder.ID_DOOR_LOCK, 0, 0x04, 0, code)
            assertEquals("unlocked", v[SwcanField.LOCK_STATE])
            assertEquals(name, v[SwcanField.LOCK_SOURCE])
        }
        assertTrue(decode(SwcanFrameDecoder.ID_DOOR_LOCK, 0, 1, 0).isEmpty())
    }

    @Test
    fun doorsHoodTrunk() {
        assertEquals("open", decode(SwcanFrameDecoder.ID_DOOR_FL, 0x01)[SwcanField.DOOR_FL])
        assertEquals("closed", decode(SwcanFrameDecoder.ID_DOOR_FR, 0x02)[SwcanField.DOOR_FR])
        assertEquals("open", decode(SwcanFrameDecoder.ID_DOOR_RL, 0x01)[SwcanField.DOOR_RL])
        assertEquals("closed", decode(SwcanFrameDecoder.ID_DOOR_RR, 0x00)[SwcanField.DOOR_RR])
        // 10-06: 08 with the hood shut (state 0, validity bit 2 clear)
        assertEquals("closed", decode(SwcanFrameDecoder.ID_HOOD, 0x08)[SwcanField.HOOD])
        assertEquals("open", decode(SwcanFrameDecoder.ID_HOOD, 0x01)[SwcanField.HOOD])
        assertEquals("open", decode(SwcanFrameDecoder.ID_HOOD, 0x02)[SwcanField.HOOD])
        assertTrue(decode(SwcanFrameDecoder.ID_HOOD, 0x04).isEmpty(), "validity bit set")
        assertEquals("open", decode(SwcanFrameDecoder.ID_TRUNK, 0x01)[SwcanField.TRUNK])
        assertTrue(decode(SwcanFrameDecoder.ID_DOOR_FL).isEmpty())
    }

    @Test
    fun alarmStates() {
        val names = mapOf(1 to "armed", 2 to "disarmed", 3 to "sounding", 4 to "arming")
        for ((code, name) in names) {
            assertEquals(name, decode(SwcanFrameDecoder.ID_THEFT_ALARM, 0, 0, 0xF8 or code)[SwcanField.ALARM])
        }
        assertTrue(decode(SwcanFrameDecoder.ID_THEFT_ALARM, 0, 0, 0).isEmpty())
        assertTrue(decode(SwcanFrameDecoder.ID_THEFT_ALARM, 0, 0).isEmpty())
    }

    @Test
    fun blowerReadsByteOneAsGmDefinesIt() {
        // 2026-09-29 capture, in order: A/C on with the fan still at rest, fan up, A/C off while the
        // fan coasts, then all off. Byte 2 (the compressor load estimate) moves with the A/C, not the fan.
        val sequence =
            listOf(
                intArrayOf(0x20, 0x00, 0x44, 0x00) to 0.0,
                intArrayOf(0x20, 0x45, 0x22, 0x00) to 27.0,
                intArrayOf(0x00, 0x45, 0x00, 0x00) to 27.0,
                intArrayOf(0x00, 0x00, 0x00, 0x00) to 0.0,
                // 2026-10-04 drive with the A/C on.
                intArrayOf(0x20, 0x73, 0x44, 0x00) to 45.0,
            )
        for ((bytes, expected) in sequence) {
            assertEquals(expected, decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, *bytes)[SwcanField.BLOWER])
        }
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, 0x20).isEmpty())
    }

    @Test
    fun cabinTempIsTheAirEstimateNotTheRoof() {
        // 2026-10-04, A/C running: cabin air 18 C (byte 4); the roof surface read 23 C (byte 5).
        assertEquals(
            18.0,
            decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0x10, 0, 0, 0, 0x74, 0x7E, 0x43)[SwcanField.CABIN_TEMP],
        )
        // Byte 0 bit 2 set: the estimate is flagged invalid.
        assertTrue(decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0x14, 0, 0, 0, 0x74, 0x7E, 0x43).isEmpty())
        assertTrue(decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0x10, 0, 0, 0).isEmpty())
    }

    @Test
    fun wheelSpeedsFromTheCar() {
        // 2026-10-04 at about 19 km/h: left driven (front), left rear, right front, right rear.
        val wheels = decode(SwcanFrameDecoder.ID_WHEEL_SPEED, 0x02, 0x53, 0x02, 0x4C, 0x02, 0x4E, 0x02, 0x4C)
        assertEquals(18.59, wheels[SwcanField.WHEEL_FL])
        assertEquals(18.38, wheels[SwcanField.WHEEL_RL])
        assertEquals(18.44, wheels[SwcanField.WHEEL_FR])
        assertEquals(18.38, wheels[SwcanField.WHEEL_RR])
        // A set validity bit (bit 6 of the wheel's first byte) drops only that wheel.
        val oneInvalid = decode(SwcanFrameDecoder.ID_WHEEL_SPEED, 0x42, 0x53, 0x02, 0x4C, 0x02, 0x4E, 0x02, 0x4C)
        assertFalse(oneInvalid.containsKey(SwcanField.WHEEL_FL))
        assertEquals(3, oneInvalid.size)
        assertTrue(decode(SwcanFrameDecoder.ID_WHEEL_SPEED, 0x02, 0x53, 0x02, 0x4C).isEmpty())
    }

    @Test
    fun tripOdometers() {
        // Trip A 2074/64 km, trip B 82406/64 km.
        val trips = decode(SwcanFrameDecoder.ID_TRIP_ODOMETER, 0, 0, 0x08, 0x1A, 0x01, 0x41, 0xE6)
        assertEquals(32.41, trips[SwcanField.TRIP_A])
        assertEquals(1287.59, trips[SwcanField.TRIP_B])
        val tripAInvalid = decode(SwcanFrameDecoder.ID_TRIP_ODOMETER, 0x80, 0, 0x08, 0x1A, 0x01, 0x41, 0xE6)
        assertEquals(setOf(SwcanField.TRIP_B), tripAInvalid.keys)
        val tripBInvalid = decode(SwcanFrameDecoder.ID_TRIP_ODOMETER, 0x40, 0, 0x08, 0x1A, 0x01, 0x41, 0xE6)
        assertEquals(setOf(SwcanField.TRIP_A), tripBInvalid.keys)
        assertTrue(decode(SwcanFrameDecoder.ID_TRIP_ODOMETER, 0, 0, 0x08, 0x1A).isEmpty())
    }

    @Test
    fun transmissionOilTemperature() {
        // 2026-10-04, late in a highway drive: 0x7B = 83 C.
        val frame = intArrayOf(0x10, 0x00, 0xC7, 0x7B, 0x9F, 0x00, 0x56, 0x4F)
        assertEquals(83.0, decode(SwcanFrameDecoder.ID_ANALOG_SLOW, *frame)[SwcanField.TRANS_OIL_TEMP])
        frame[0] = frame[0] or 0x20
        assertTrue("validity bit set", decode(SwcanFrameDecoder.ID_ANALOG_SLOW, *frame).isEmpty())
        assertTrue(decode(SwcanFrameDecoder.ID_ANALOG_SLOW, 0x10, 0, 0xC7).isEmpty())
    }

    @Test
    fun compressorPowerAndEnergySplit() {
        // 2026-10-04: 0x1D = 1.16 kW of A/C compressor power.
        assertEquals(1.16, decode(SwcanFrameDecoder.ID_CLIMATE_POWER, 0, 0, 0, 0, 0x1D)[SwcanField.AC_COMPRESSOR_KW])
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_POWER, 0, 0, 0, 0).isEmpty())
        // 2026-10-04, 11 minutes in: 4.0 kWh driving, 0.2 climate, none conditioning, 8.5 left.
        val split = decode(SwcanFrameDecoder.ID_ENERGY_SPLIT, 0x00, 0x28, 0x00, 0x02, 0x00, 0x00, 0x00, 0x55)
        assertEquals(4.0, split[SwcanField.CYCLE_DRIVING_ENERGY])
        assertEquals(0.2, split[SwcanField.CYCLE_CLIMATE_ENERGY])
        assertEquals(0.0, split[SwcanField.CYCLE_CONDITIONING_ENERGY])
        assertEquals(8.5, split[SwcanField.BATTERY_ENERGY_LEFT])
        // The top two bits of each pair aren't part of the count; all-ones means not available.
        val partial = decode(SwcanFrameDecoder.ID_ENERGY_SPLIT, 0xC0, 0x28, 0x3F, 0xFF, 0x00, 0x00, 0x00, 0x55)
        assertEquals(4.0, partial[SwcanField.CYCLE_DRIVING_ENERGY])
        assertFalse(partial.containsKey(SwcanField.CYCLE_CLIMATE_ENERGY))
        assertTrue(decode(SwcanFrameDecoder.ID_ENERGY_SPLIT, 0, 0x28, 0, 2).isEmpty())
    }

    @Test
    fun climateFrames() {
        val compressor = decode(SwcanFrameDecoder.ID_AC_COMPRESSOR, 0, 0x60, 0xD1, 0x94)
        assertEquals(8.0, compressor[SwcanField.AC_EVAP_TEMP])
        assertEquals(4500.0, compressor[SwcanField.AC_COMPRESSOR_RPM])
        assertTrue(decode(SwcanFrameDecoder.ID_AC_COMPRESSOR, 0, 0x60).isEmpty())
        assertEquals(1.2, decode(SwcanFrameDecoder.ID_COOLANT_HEATER, 0, 30, 0)[SwcanField.COOLANT_HEATER_KW])
        assertTrue(decode(SwcanFrameDecoder.ID_COOLANT_HEATER, 0, 30).isEmpty())
        assertEquals(32.0, decode(SwcanFrameDecoder.ID_PE_COOLANT, 0, 72)[SwcanField.PE_COOLANT_TEMP])
        assertTrue(decode(SwcanFrameDecoder.ID_PE_COOLANT, 0).isEmpty())
        assertEquals(45.0, decode(SwcanFrameDecoder.ID_HEATER_CORE, 0, 0, 85)[SwcanField.HEATER_CORE_TEMP])
        assertTrue(decode(SwcanFrameDecoder.ID_HEATER_CORE, 0, 0, 0).isEmpty())
        assertEquals(32.0, decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, 0x20, 0x51, 0x24, 0)[SwcanField.BLOWER])
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, 0).isEmpty())
        assertEquals(21.0, decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0, 0, 0, 0, 0x7A)[SwcanField.CABIN_TEMP])
        assertTrue(decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0, 0, 0).isEmpty())
        assertEquals("on", decode(SwcanFrameDecoder.ID_CLIMATE_GENERAL, 0x20)[SwcanField.AC_STATE])
        assertEquals("off", decode(SwcanFrameDecoder.ID_CLIMATE_GENERAL, 0x10)[SwcanField.AC_STATE])
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_GENERAL, 0x30).isEmpty())
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_GENERAL).isEmpty())
    }

    @Test
    fun chargeCurrentLimit() {
        // levels [12, 8, 0, 0], level 0 in force
        assertEquals(12.0, decode(SwcanFrameDecoder.ID_CHARGE_LIMIT, 0, 0, 0, 0x06, 0x20, 0)[SwcanField.CHARGE_LIMIT])
        // same levels, level 1 in force
        assertEquals(8.0, decode(SwcanFrameDecoder.ID_CHARGE_LIMIT, 0, 0, 0, 0x16, 0x20, 0)[SwcanField.CHARGE_LIMIT])
        // level 2 selected but only two are offered: invalid, dropped
        assertTrue(decode(SwcanFrameDecoder.ID_CHARGE_LIMIT, 0, 0, 0, 0x26, 0x20, 0).isEmpty())
        // all four offered [12, 8, 12, 4], level 3 in force
        val four = decode(SwcanFrameDecoder.ID_CHARGE_LIMIT, 0, 0, 0, 0x36, 0x20 or 0x01, 0x80 or 0x04)
        assertEquals(4.0, four[SwcanField.CHARGE_LIMIT])
        assertTrue(decode(SwcanFrameDecoder.ID_CHARGE_LIMIT, 0, 0, 0, 0x06).isEmpty())
    }

    // ---- GMLAN-PID matched frames ------------------------------------------------------------

    @Test
    fun rangesMatchOnPidRegardlessOfSource() {
        assertEquals(55.0, decode(0x102EC0CB, 0, 0x1B, 0x80, 0, 0, 0, 0, 0)[SwcanField.CLUSTER_EV_RANGE])
        // different priority/source bits, same PID 0x176
        assertEquals(55.0, decode(0x0C2EC040, 0, 0x1B, 0x80)[SwcanField.CLUSTER_EV_RANGE])
        assertTrue(decode(0x102EC0CB, 0, 0x1B).isEmpty())
        assertEquals(471.0, decode(0x104480CB, 0, 0, 0x75, 0xC0)[SwcanField.FUEL_RANGE])
        // The 2017 Volt sends the gas range from source 0x60 (on-car capture, 2026-09-29).
        assertEquals(378.0, decode(0x10448060, 0, 0, 0x5E, 0x8D)[SwcanField.FUEL_RANGE])
        assertTrue(decode(0x104480CB, 0, 0, 0, 0).isEmpty(), "0 = car not on yet")
        assertTrue(decode(0x104480CB, 0, 0, 0x75).isEmpty())
    }

    @Test
    fun driveCycleCounters() {
        assertEquals(8.3, decode(0x102820CB, 0, 0, 0, 0x53, 0, 0, 0, 0)[SwcanField.CYCLE_ENERGY_USED])
        assertEquals(8.3, decode(0x102820CB, 0, 0, 0xC0, 0x53, 0, 0, 0, 0)[SwcanField.CYCLE_ENERGY_USED])
        assertTrue(decode(0x102820CB, 0, 0, 0, 0x53).isEmpty())
        val dist = decode(0x1044A0CB, 0x06, 0x80, 0x00, 0x20, 0x00, 0, 0, 0)
        assertEquals(52.0, dist[SwcanField.CYCLE_EV_DISTANCE])
        // fuel km: 17 bits from bit 22 = byte2 bits 6..0, byte3, byte4 bits 7..6; 0x20 in byte3 = raw 128 = 2 km
        assertEquals(2.0, dist[SwcanField.CYCLE_FUEL_DISTANCE])
        assertTrue(decode(0x1044A0CB, 0x06, 0x80).isEmpty())
        // fuel used 12 bits from bit 51: byte6 bits 3..0 + byte7; 0x028 = 40 * 0.125 = 5 L
        assertEquals(5.0, decode(0x104460CB, 0, 0, 0, 0, 0, 0, 0x00, 0x28)[SwcanField.CYCLE_FUEL_USED])
        assertTrue(decode(0x104460CB, 0, 0, 0, 0, 0, 0, 0x0F, 0xFF).isEmpty(), "0xFFF = not available")
        assertTrue(decode(0x104460CB, 0, 0).isEmpty())
    }

    @Test
    fun windowsReadEachWindowOnItsOwn() {
        val windowId = gmlan(SwcanFrameDecoder.PID_WINDOWS)
        // 10-06, parked with the windows up: 28 2D is driver 0, the other three 5 (no reading)
        val parked = decode(windowId, 0x28, 0x2D)
        assertEquals(mapOf(SwcanField.WINDOW_FL to 0.0), parked)
        // the driver window moving while the others still send 5
        assertEquals(mapOf(SwcanField.WINDOW_FL to 50.0), decode(windowId, 0x2B, 0x2D))
        // driver 3, left rear 0 while the right side carries 5
        val moving = decode(windowId, 0x03, 0x2D)
        assertEquals(50.0, moving[SwcanField.WINDOW_FL])
        assertEquals(0.0, moving[SwcanField.WINDOW_RL])
        assertEquals(2, moving.size)
        // all four real: drv 6, LR 0, pass 6, RR 0
        val all = decode(windowId, 0x06, 0x06)
        assertEquals(100.0, all[SwcanField.WINDOW_FL])
        assertEquals(0.0, all[SwcanField.WINDOW_RL])
        assertEquals(100.0, all[SwcanField.WINDOW_FR])
        assertEquals(0.0, all[SwcanField.WINDOW_RR])
        // 7 clamps to fully open
        assertEquals(100.0, decode(windowId, 0x07, 0x00)[SwcanField.WINDOW_FL])
        assertTrue(decode(windowId, 0x06).isEmpty())
    }

    @Test
    fun oilLifeFromTheEngineBroadcast() {
        // 10-06: 01 5D 01 0D B1 00 00 FF while the cluster showed about 70 %
        val oil = decode(gmlan(SwcanFrameDecoder.PID_ENGINE_INFO_4), 0x01, 0x5D, 0x01, 0x0D, 0xB1, 0x00, 0x00, 0xFF)
        assertEquals(69.0, oil[SwcanField.OIL_LIFE])
        assertEquals(100.0, decode(gmlan(SwcanFrameDecoder.PID_ENGINE_INFO_4), 0, 0, 0, 0, 0xFF)[SwcanField.OIL_LIFE])
        assertTrue(decode(gmlan(SwcanFrameDecoder.PID_ENGINE_INFO_4), 0x01, 0x5D, 0x01, 0x0D).isEmpty())
    }

    @Test
    fun theCarsOwnWarningFramesLightNothing() {
        // Every capture so far, with no light on the dash: none of these may read as a warning.
        assertEquals(
            "",
            decode(gmlan(SwcanFrameDecoder.PID_WARNINGS_FAST), 0x04, 0x0E, 0, 0, 0, 0, 0, 0)[SwcanField.WARNINGS_FAST],
        )
        assertEquals(
            "",
            decode(
                gmlan(SwcanFrameDecoder.PID_WARNINGS_SLOW),
                0x00,
                0x00,
                0x01,
                0x00,
                0x08,
                0x00,
                0x00,
                0x22,
            )[SwcanField.WARNINGS_SLOW],
        )
        assertEquals(
            "",
            decode(
                gmlan(SwcanFrameDecoder.PID_WARNINGS_SUPER_SLOW),
                0x08,
                0,
                0,
                0,
                0,
                0,
            )[SwcanField.WARNINGS_SUPER_SLOW],
        )
        assertEquals("", decode(gmlan(SwcanFrameDecoder.PID_WASHER_LEVEL), 0x00)[SwcanField.WARNING_WASHER])
        assertEquals("", decode(gmlan(SwcanFrameDecoder.PID_BULB_OUTAGE), 0x00, 0x00)[SwcanField.WARNING_BULBS])
    }

    @Test
    fun warningBitsNameTheirLights() {
        assertEquals("abs", decode(gmlan(SwcanFrameDecoder.PID_WARNINGS_FAST), 0x01)[SwcanField.WARNINGS_FAST])
        val slow = gmlan(SwcanFrameDecoder.PID_WARNINGS_SLOW)
        assertEquals("tire_pressure_low", decode(slow, 0x04, 0x00)[SwcanField.WARNINGS_SLOW])
        assertEquals("brake_pads,brake_system", decode(slow, 0x00, 0xA0)[SwcanField.WARNINGS_SLOW])
        assertEquals("brake_fluid_low", decode(slow, 0x80, 0x00)[SwcanField.WARNINGS_SLOW])
        assertEquals("brake fluid flagged invalid", "", decode(slow, 0x90, 0x00)[SwcanField.WARNINGS_SLOW])
        val superSlow = gmlan(SwcanFrameDecoder.PID_WARNINGS_SUPER_SLOW)
        assertEquals("oil_hot", decode(superSlow, 0x20, 0x00, 0x00)[SwcanField.WARNINGS_SUPER_SLOW])
        assertEquals(
            "oil_change,oil_pressure_low,engine_hot,steering_assist_reduced",
            decode(superSlow, 0x00, 0x8A, 0x40)[SwcanField.WARNINGS_SUPER_SLOW],
        )
        assertTrue(decode(superSlow, 0x00, 0x8A).isEmpty(), "too short for byte 2")
        assertEquals(
            "washer_fluid_low",
            decode(gmlan(SwcanFrameDecoder.PID_WASHER_LEVEL), 0x01)[SwcanField.WARNING_WASHER],
        )
        val bulbs = gmlan(SwcanFrameDecoder.PID_BULB_OUTAGE)
        assertEquals("bulb_center_brake", decode(bulbs, 0x01, 0x00)[SwcanField.WARNING_BULBS])
        assertEquals("bulb_reverse,bulb_right_daytime", decode(bulbs, 0x00, 0xA0)[SwcanField.WARNING_BULBS])
    }

    private fun gmlan(pid: Int) = 0x10000000 or (pid shl 13) or 0x40

    @Test
    fun ignoresUnknownAndElevenBitFrames() {
        assertTrue(decode(0x10002097, 1, 2, 3).isEmpty())
        assertTrue(SwcanFrameDecoder.decode(SwcanFrame(0x124, false, intArrayOf(0, 0, 0x60, 0xD9, 0, 0xFC))).isEmpty())
    }

    @Test
    fun dbcBigEndianWalksMotorolaBits() {
        val d = intArrayOf(0x01, 0x80, 0xFF)
        assertEquals(0x0180L, SwcanFrameDecoder.dbcBigEndian(d, 7, 16))
        assertEquals(0x1L, SwcanFrameDecoder.dbcBigEndian(d, 0, 1))
        // bit 0 then byte 1 bit 7: 1, 1
        assertEquals(0x3L, SwcanFrameDecoder.dbcBigEndian(d, 0, 2))
        assertNull(SwcanFrameDecoder.dbcBigEndian(d, 16, 9))
    }

    private fun assertTrue(
        value: Boolean,
        message: String,
    ) = assertTrue(message, value)

    @Test
    fun remoteStartStatus() {
        assertEquals("off", decode(SwcanFrameDecoder.ID_REMOTE_START, 0x00)[SwcanField.REMOTE_START])
        assertEquals("on", decode(SwcanFrameDecoder.ID_REMOTE_START, 0x02)[SwcanField.REMOTE_START])
        assertEquals("on", decode(SwcanFrameDecoder.ID_REMOTE_START, 0x03)[SwcanField.REMOTE_START])
        assertTrue(decode(SwcanFrameDecoder.ID_REMOTE_START, 0x01).isEmpty())
        assertTrue(decode(SwcanFrameDecoder.ID_REMOTE_START).isEmpty())
    }
}
