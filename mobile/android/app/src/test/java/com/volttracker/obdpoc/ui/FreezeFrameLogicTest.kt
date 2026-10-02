package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.FreezeFrame
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.FreezeFrameRow
import com.volttracker.obdpoc.ui.diag.FreezeFrameSnapshot
import com.volttracker.obdpoc.ui.diag.freezeFrameDtc
import com.volttracker.obdpoc.ui.diag.freezeFrameLine
import com.volttracker.obdpoc.ui.diag.freezeFrameNote
import com.volttracker.obdpoc.ui.diag.freezeFrameRows
import com.volttracker.obdpoc.ui.diag.hasFreezeFrame
import com.volttracker.obdpoc.ui.units.VoltUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeFrameLogicTest {
    private val snapshot =
        FreezeFrameSnapshot(
            "P0420",
            1L,
            listOf(
                FreezeFrame.Reading("vehicle speed", 72.0, "km/h"),
                FreezeFrame.Reading("engine rpm", 1726.0, "rpm"),
                FreezeFrame.Reading("engine load", 38.4, "%"),
                FreezeFrame.Reading("coolant temperature", 88.0, "deg C"),
                FreezeFrame.Reading("control module voltage", 14.23, "V"),
                FreezeFrame.Reading("engine run time", 125.0, "s"),
                FreezeFrame.Reading("mystery", 2.0, "g/s"),
            ),
        )

    @Test
    fun readingsShowInPlainWordsAndTheChosenUnits() {
        assertEquals(
            listOf(
                FreezeFrameRow("Speed", "45 mph"),
                FreezeFrameRow("Engine RPM", "1726 rpm"),
                FreezeFrameRow("Engine load", "38%"),
                FreezeFrameRow("Coolant", "190°F"),
                FreezeFrameRow("12 V system", "14.2 V"),
                FreezeFrameRow("Engine run time", "2 min 5 s"),
                FreezeFrameRow("Mystery", "2.0 g/s"),
            ),
            freezeFrameRows(snapshot, VoltUnits()),
        )
        val metric = freezeFrameRows(snapshot, VoltUnits(metric = true))
        assertEquals("72 km/h", metric[0].value)
        assertEquals("88°C", metric[3].value)
    }

    @Test
    fun theCodeComesFromTheSnapshotElseTheSavedRow() {
        assertNull(DiagUiState().freezeFrameDtc())
        assertFalse(DiagUiState().hasFreezeFrame())
        assertEquals("Not scanned yet", DiagUiState().freezeFrameLine())

        val row = DiagUiState(codes = listOf(DtcCode("P0171", status = DtcCode.STATUS_FREEZE_FRAME)))
        assertEquals("P0171", row.freezeFrameDtc())
        assertTrue(row.freezeFrameNote().contains("haven't been read yet"))

        val read = row.copy(freezeFrame = snapshot)
        assertEquals("Captured with P0420", read.freezeFrameLine())
        assertTrue(read.freezeFrameNote().contains("the moment it set P0420"))
    }

    @Test
    fun theDemoHasAFreezeFrameToShow() {
        assertTrue(DiagUiState.demo.hasFreezeFrame())
        assertEquals(7, freezeFrameRows(DiagUiState.demo.freezeFrame, VoltUnits()).size)
    }
}
