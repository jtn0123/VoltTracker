package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject

/**
 * Captures the Bluetooth-subsystem state that the OS won't otherwise log.
 *
 * Responsibilities:
 *
 * - **Pre-flight** ([onPreConnect]): right before the engine opens an RFCOMM socket, snapshot the
 *   bond state, device type, adapter discovery flag, and cached UUIDs into a `bluetooth_preflight`
 *   + `bond_sdp_snapshot` event. This is the data we need to tell "stale bond" apart from
 *   "adapter wedged" after the fact.
 * - **OS broadcasts**: register a [BroadcastReceiver] for [BluetoothDevice.ACTION_ACL_CONNECTED],
 *   [BluetoothDevice.ACTION_ACL_DISCONNECTED], [BluetoothDevice.ACTION_BOND_STATE_CHANGED],
 *   [BluetoothDevice.ACTION_UUID], and [BluetoothAdapter.ACTION_STATE_CHANGED]. Each broadcast for
 *   the active OBD address (or the adapter overall) is logged with structured fields.
 * - **SDP refresh on repeat failure**: subscribe to our own [ObdService.BROADCAST_STATUS]
 *   broadcast; when we see the state transition to "connecting" twice in a row while still
 *   carrying a non-null `failureClass`, kick off a one-shot SDP refresh via [SdpProbe] so the next
 *   attempt has fresh UUIDs.
 *
 * All log writes go through [SessionRecorder.logEvent] so they land in the session JSONL alongside
 * the engine's own events; the dashboard reads them via the same path.
 *
 * Lifecycle: [register] in [ObdService.onCreate], [unregister] in [ObdService.onDestroy].
 */
class BluetoothStateReporter(
    private val service: ObdService?,
    private val sdpProbe: SdpProbe?,
) {
    private val receivers = BroadcastReceiverGroup()

    /** Address of the device the engine is currently attempting / using. Set in pre-flight. */
    @Volatile private var activeAddress: String? = null

    /** Tracks consecutive status broadcasts in `connecting` with a non-null failureClass. */
    @Volatile private var connectingWithFailureStreak = 0

    /**
     * Registers the OS-level Bluetooth receiver plus our own status receiver. Caller (the service)
     * decides whether to call this — we don't gate on permissions internally beyond skipping the
     * OS-broadcast subset that needs `BLUETOOTH_CONNECT`, because the status-broadcast receiver
     * doesn't need any BT permission.
     */
    fun register(context: Context?) {
        if (context == null) {
            return
        }
        // Both registrations swallow RuntimeException so a quirky shim path (e.g. an older API
        // level back-filling RECEIVER_NOT_EXPORTED via the auto-declared
        // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) can't take down service start. The observability
        // is a "nice to have" — the engine must still come up so the user can connect.
        try {
            registerSystemReceiver(context)
        } catch (ex: RuntimeException) {
            logRegisterSkipped("system", ex)
        }
        try {
            registerStatusReceiver(context)
        } catch (ex: RuntimeException) {
            logRegisterSkipped("status", ex)
        }
    }

    private fun logRegisterSkipped(
        which: String,
        ex: RuntimeException,
    ) {
        val recorder = service?.recorder ?: return
        recorder.logEvent(
            "bluetooth_reporter_register_skipped",
            "which",
            which,
            "reason",
            ex.message ?: ex.javaClass.simpleName,
        )
    }

    fun unregister(context: Context?) {
        if (context == null) {
            return
        }
        receivers.unregisterAll(context)
        activeAddress = null
        connectingWithFailureStreak = 0
    }

    /**
     * Pre-flight hook called from [ObdPollingEngine.connectAndInitialize] immediately before the
     * RFCOMM socket open. Snapshots the device/bond/adapter state in a single helper because both
     * need the same [BluetoothDevice] resolution.
     */
    @SuppressLint("MissingPermission")
    fun onPreConnect(address: String?) {
        if (address == null || address.trim().isEmpty()) {
            return
        }
        // Only reset the failure streak when we switch to a different device. Resetting on every
        // pre-flight defeated the repeat-failure SDP refresh: two consecutive failed connects to the
        // *same* device must be able to reach the streak threshold and trigger the UUID refresh.
        if (!address.equals(activeAddress, ignoreCase = true)) {
            connectingWithFailureStreak = 0
        }
        activeAddress = address
        val service = service ?: return
        val recorder = service.recorder ?: return
        val adapter = BluetoothAdapters.get(service)
        if (adapter == null) {
            recorder.logEvent("bluetooth_preflight", "address", address, "reason", "no_adapter")
            return
        }
        val hasConnectPerm = service.hasBluetoothConnectPermission()
        val hasScanPerm = service.hasBluetoothScanPermission()

        val adapterState = describeAdapterState(adapter.state)
        var discovering: Boolean? = null
        if (hasScanPerm) {
            try {
                discovering = adapter.isDiscovering
            } catch (_: SecurityException) {
                // hasScanPerm guards this, but the OS still throws when the perm is revoked
                // mid-process; null is the right "we don't know" value.
            }
        }

        val device =
            try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                recorder.logEvent(
                    "bluetooth_preflight",
                    "address",
                    address,
                    "reason",
                    "invalid_address",
                    "adapterState",
                    adapterState,
                )
                return
            }

        var deviceName = ""
        var bondState = -1
        var deviceType = BluetoothDevice.DEVICE_TYPE_UNKNOWN
        var uuids: Array<ParcelUuid>? = null
        if (hasConnectPerm) {
            try {
                deviceName = safeString(device.name)
                bondState = device.bondState
                deviceType = device.type
                uuids = device.uuids
            } catch (_: SecurityException) {
                // hasConnectPerm guards these, but if the OS still revokes mid-call we just log
                // the partial snapshot.
            }
        }

        recorder.logEvent(
            "bluetooth_preflight",
            "address",
            address,
            "deviceName",
            deviceName,
            "bondState",
            describeBondState(bondState),
            "deviceType",
            describeDeviceType(deviceType),
            "adapterState",
            adapterState,
            "discovering",
            discovering?.toString() ?: "unknown",
            "hasConnectPerm",
            hasConnectPerm.toString(),
            "hasScanPerm",
            hasScanPerm.toString(),
        )

        // Bond/SDP snapshot kept as a separate event so dashboards can index it independently.
        recorder.logEvent(
            "bond_sdp_snapshot",
            "address",
            address,
            "bondState",
            describeBondState(bondState),
            "deviceType",
            describeDeviceType(deviceType),
            "uuids",
            uuids?.contentToString() ?: "[]",
        )
    }

    private fun registerSystemReceiver(context: Context) {
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_UUID)
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)

        val systemReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    handleSystemBroadcast(intent)
                }
            }
        // Bluetooth ACTION_* intents are protected system broadcasts: the platform delivers them
        // to NOT_EXPORTED receivers on API 33+ (exported is only required for app-to-app
        // broadcasts), so keep this receiver closed to other apps.
        receivers.register(context, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun registerStatusReceiver(context: Context) {
        val statusFilter = IntentFilter(ObdService.BROADCAST_STATUS)
        val statusReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    handleStatusBroadcast(intent)
                }
            }
        // Our own status broadcast is package-private (Intent.setPackage); not exported.
        receivers.register(context, statusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    private fun handleSystemBroadcast(intent: Intent) {
        val action = intent.action ?: return
        val recorder = service?.recorder ?: return
        if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val previous =
                intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
            recorder.logEvent(
                "bluetooth_state_changed",
                "state",
                describeAdapterState(state),
                "previous",
                describeAdapterState(previous),
            )
            return
        }
        // The remaining actions all carry an EXTRA_DEVICE.
        val device = bluetoothDeviceExtra(intent)
        val address = if (device == null) "" else safeString(device.address)
        // Only log events for the device we're actually trying to talk to — keeps the log from
        // being flooded by every nearby keyboard/headset/watch on the user's phone.
        val tracking = activeAddress
        if (!tracking.isNullOrEmpty() && !tracking.equals(address, ignoreCase = true)) {
            return
        }
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                recorder.logEvent("bluetooth_acl_connected", "address", address)

            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                recorder.logEvent("bluetooth_acl_disconnected", "address", address)

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous =
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR,
                    )
                recorder.logEvent(
                    "bluetooth_bond_state_changed",
                    "address",
                    address,
                    "bondState",
                    describeBondState(bondState),
                    "previous",
                    describeBondState(previous),
                )
            }

            BluetoothDevice.ACTION_UUID -> {
                var uuids = "[]"
                val raw = uuidArrayExtra(intent)
                if (raw != null) {
                    uuids = raw.contentToString()
                }
                recorder.logEvent("sdp_refresh", "address", address, "uuids", uuids)
            }
        }
    }

    private fun handleStatusBroadcast(intent: Intent) {
        val payload = intent.getStringExtra(ObdService.EXTRA_JSON)
        if (payload.isNullOrEmpty()) {
            return
        }
        val state: String
        val failureClass: String?
        try {
            val json = JSONObject(payload)
            state = json.optString("state", "")
            failureClass = if (json.has("failureClass")) json.optString("failureClass") else null
        } catch (_: JSONException) {
            return
        }
        val connecting = "connecting" == state
        val haveFailure = !failureClass.isNullOrEmpty()
        if (connecting && haveFailure) {
            connectingWithFailureStreak += 1
            // Two consecutive failed connect attempts → ask the OS for fresh SDP UUIDs.
            if (connectingWithFailureStreak >= 2 && sdpProbe != null) {
                sdpProbe.trigger(activeAddress)
            }
        } else {
            // Anything that isn't a "connecting with failureClass" broadcast breaks the streak.
            // The previous version only reset on non-`connecting` states, which left stale streak
            // state across a `connecting + failure → connecting (no failureClass) → connecting +
            // failure` sequence — non-consecutive failures could fire a spurious SDP refresh.
            connectingWithFailureStreak = 0
        }
    }

    /** Test-only: visibility of streak state so tests can assert SDP-refresh logic. */
    fun connectingWithFailureStreakForTest(): Int = connectingWithFailureStreak

    /** Test-only: pump a status payload through the handler without going through the IntentFilter. */
    fun handleStatusForTest(
        state: String?,
        failureClass: String?,
    ) {
        val json = JSONObject()
        try {
            json.put("state", state)
            if (failureClass != null) {
                json.put("failureClass", failureClass)
            }
        } catch (_: JSONException) {
            // Building from constants — this can't actually fire, but the JSON API forces a catch.
            return
        }
        val intent = Intent(ObdService.BROADCAST_STATUS)
        intent.putExtra(ObdService.EXTRA_JSON, json.toString())
        handleStatusBroadcast(intent)
    }

    companion object {
        // The typed getParcelableExtra(String, Class) overload requires API 33; minSdk 23 still
        // needs the deprecated overload on the pre-Tiramisu branch.
        @Suppress("DEPRECATION")
        private fun bluetoothDeviceExtra(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        // The typed getParcelableArrayExtra(String, Class) overload requires API 33; minSdk 23
        // still needs the deprecated overload on the pre-Tiramisu branch.
        @Suppress("DEPRECATION")
        private fun uuidArrayExtra(intent: Intent): Array<out Parcelable>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
            } else {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            }

        private fun describeAdapterState(state: Int): String =
            when (state) {
                BluetoothAdapter.STATE_OFF -> "off"
                BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                BluetoothAdapter.STATE_ON -> "on"
                BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                BluetoothAdapter.ERROR -> "error"
                else -> "unknown($state)"
            }

        private fun describeBondState(state: Int): String =
            when (state) {
                BluetoothDevice.BOND_NONE -> "none"
                BluetoothDevice.BOND_BONDING -> "bonding"
                BluetoothDevice.BOND_BONDED -> "bonded"
                else -> "unknown($state)"
            }

        private fun describeDeviceType(type: Int): String =
            when (type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "le"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "unknown($type)"
            }

        private fun safeString(value: String?): String = value ?: ""
    }
}
