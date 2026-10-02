package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.settings.AdapterListState
import com.volttracker.obdpoc.ui.settings.PairedAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairedAdapterReaderTest {
    @Test
    fun parsesPairedDevicesAndSkipsOnesWithoutAnAddress() {
        val json =
            """[{"name":"OBDLink MX+","address":"00:11:22:33:44:55","obdCandidate":true},
               {"name":"Earbuds","address":"66:77:88:99:AA:BB","obdCandidate":false},
               {"name":"Broken","address":""}]"""
        assertEquals(
            listOf(
                PairedAdapter("OBDLink MX+", "00:11:22:33:44:55", likelyObd = true),
                PairedAdapter("Earbuds", "66:77:88:99:AA:BB", likelyObd = false),
            ),
            PairedAdapterReader.parse(json),
        )
    }

    @Test
    fun malformedJsonIsAnEmptyList() {
        assertEquals(emptyList<PairedAdapter>(), PairedAdapterReader.parse("not json"))
    }

    @Test
    fun listStateNamesWhatBlocksTheList() {
        assertEquals(AdapterListState.NO_BLUETOOTH, PairedAdapterReader.listState(false, true, true))
        assertEquals(AdapterListState.NEEDS_PERMISSION, PairedAdapterReader.listState(true, false, true))
        assertEquals(AdapterListState.BLUETOOTH_OFF, PairedAdapterReader.listState(true, true, false))
        assertEquals(AdapterListState.READY, PairedAdapterReader.listState(true, true, true))
    }
}
