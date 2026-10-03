package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SW-CAN decoders to the OVMS vehicle_voltampera math with synthetic frames. The frames
 * are built by hand from each decoder's bit layout, so these tests prove the port matches OVMS —
 * NOT that the values are right on the car (that needs a real capture).
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
        assertEquals("open", decode(SwcanFrameDecoder.ID_HOOD, 0x02)[SwcanField.HOOD])
        assertEquals("closed", decode(SwcanFrameDecoder.ID_HOOD, 0x01)[SwcanField.HOOD])
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
        assertEquals(39.0, decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, 0, 100)[SwcanField.BLOWER])
        assertTrue(decode(SwcanFrameDecoder.ID_CLIMATE_BASIC, 0).isEmpty())
        assertEquals(21.0, decode(SwcanFrameDecoder.ID_CABIN_TEMP, 0, 0, 0, 0, 0, 0x7A)[SwcanField.CABIN_TEMP])
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
    fun windowsSkipIdleFiller() {
        val windowId = 0x10000000 or (SwcanFrameDecoder.PID_WINDOWS shl 13) or 0x40
        // the BCM's resting pattern 28 2d: driver 0, others 5 → nothing
        assertTrue(decode(windowId, 0x28, 0x2D).isEmpty())
        // driver 3, left rear 0 while the right side carries filler 5
        val moving = decode(windowId, 0x03, 0x2D)
        assertEquals(50.0, moving[SwcanField.WINDOW_FL])
        assertEquals(0.0, moving[SwcanField.WINDOW_RL])
        assertEquals(2, moving.size)
        // the driver alone moving while all three others are filler is indistinguishable from idle
        // (OVMS drops it too)
        assertTrue(decode(windowId, 0x2B, 0x2D).isEmpty())
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
