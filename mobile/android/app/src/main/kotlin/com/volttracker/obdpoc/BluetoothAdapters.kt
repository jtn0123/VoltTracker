package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

object BluetoothAdapters {
    @JvmStatic
    fun get(context: Context?): BluetoothAdapter? {
        if (context == null) {
            return null
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter
    }
}
