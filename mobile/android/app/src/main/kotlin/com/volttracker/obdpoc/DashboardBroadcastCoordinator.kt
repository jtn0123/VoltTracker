package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Owns dashboard-facing broadcast registration for the Activity lifecycle.
 */
class DashboardBroadcastCoordinator(
    private val onTelemetryJson: (String) -> Unit,
    private val onStatusJson: (String) -> Unit,
    private val onAutoConnectTrigger: (String, String?) -> Unit,
    private val bluetoothDeviceAddress: (Intent) -> String,
) {
    private val receivers = BroadcastReceiverGroup()

    private val obdReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val json = intent.getStringExtra(ObdService.EXTRA_JSON) ?: "{}"
                when (intent.action) {
                    ObdService.BROADCAST_TELEMETRY -> onTelemetryJson(json)
                    ObdService.BROADCAST_STATUS -> onStatusJson(json)
                }
            }
        }

    private val autoConnectReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED ->
                        onAutoConnectTrigger(
                            AutoConnectController.TRIGGER_BLUETOOTH_CONNECTED,
                            bluetoothDeviceAddress(intent),
                        )

                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_ON) {
                            onAutoConnectTrigger(AutoConnectController.TRIGGER_BLUETOOTH_ON, null)
                        }
                    }
                }
            }
        }

    fun register(context: Context) {
        val obdFilter =
            IntentFilter().apply {
                addAction(ObdService.BROADCAST_TELEMETRY)
                addAction(ObdService.BROADCAST_STATUS)
            }
        receivers.register(context, obdReceiver, obdFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val autoConnectFilter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
        receivers.register(context, autoConnectReceiver, autoConnectFilter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregisterAll(context: Context) {
        receivers.unregisterAll(context)
    }
}
