package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.settings.AdapterListState
import com.volttracker.obdpoc.ui.settings.PairedAdapter
import org.json.JSONArray
import org.json.JSONException

/** Turns [DeviceCatalog.getBondedDevicesJson] into the Settings → Adapter picker's rows. */
internal object PairedAdapterReader {
    fun parse(json: String): List<PairedAdapter> =
        try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { item ->
                    item.optString("address").takeIf { it.isNotBlank() }?.let { address ->
                        PairedAdapter(
                            name = item.optString("name"),
                            address = address,
                            likelyObd = item.optBoolean("obdCandidate"),
                        )
                    }
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }

    fun listState(
        hasBluetooth: Boolean,
        hasPermission: Boolean,
        bluetoothEnabled: Boolean,
    ): AdapterListState =
        when {
            !hasBluetooth -> AdapterListState.NO_BLUETOOTH
            !hasPermission -> AdapterListState.NEEDS_PERMISSION
            !bluetoothEnabled -> AdapterListState.BLUETOOTH_OFF
            else -> AdapterListState.READY
        }
}
