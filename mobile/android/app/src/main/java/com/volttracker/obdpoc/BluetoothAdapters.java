package com.volttracker.obdpoc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

final class BluetoothAdapters {

    private BluetoothAdapters() {}

    static BluetoothAdapter get(Context context) {
        if (context == null) {
            return null;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
}
