package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.Parcelable;
import androidx.core.content.ContextCompat;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bucket 2 (A5/A6/B4/B5): captures the Bluetooth-subsystem state that the OS won't otherwise log.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li><b>Pre-flight</b> ({@link #onPreConnect(String)}): right before the engine opens an RFCOMM
 *       socket, snapshot the bond state, device type, adapter discovery flag, and cached UUIDs into
 *       a {@code bluetooth_preflight} + {@code bond_sdp_snapshot} event. This is the data we need
 *       to tell "stale bond" apart from "adapter wedged" after the fact.
 *   <li><b>OS broadcasts</b>: register a {@link BroadcastReceiver} for {@link
 *       BluetoothDevice#ACTION_ACL_CONNECTED}, {@link BluetoothDevice#ACTION_ACL_DISCONNECTED},
 *       {@link BluetoothDevice#ACTION_BOND_STATE_CHANGED}, {@link BluetoothDevice#ACTION_UUID}, and
 *       {@link BluetoothAdapter#ACTION_STATE_CHANGED}. Each broadcast for the active OBD address
 *       (or the adapter overall) is logged with structured fields.
 *   <li><b>SDP refresh on repeat failure</b>: subscribe to our own {@link
 *       ObdService#BROADCAST_STATUS} broadcast; when we see the state transition to "connecting"
 *       twice in a row while still carrying a non-null {@code failureClass}, kick off a one-shot
 *       SDP refresh via {@link SdpProbe} so the next attempt has fresh UUIDs.
 * </ul>
 *
 * <p>All log writes go through {@link SessionRecorder#logEvent(String, String...)} so they land in
 * the session JSONL alongside the engine's own events; the dashboard reads them via the same path.
 *
 * <p>Lifecycle: {@link #register(Context)} in {@link ObdService#onCreate()}, {@link
 * #unregister(Context)} in {@link ObdService#onDestroy()}.
 */
final class BluetoothStateReporter {

    private final ObdService service;
    private final SdpProbe sdpProbe;
    private final BroadcastReceiverGroup receivers = new BroadcastReceiverGroup();

    /** Address of the device the engine is currently attempting / using. Set in pre-flight. */
    private volatile String activeAddress;

    /** Tracks consecutive status broadcasts in {@code connecting} with a non-null failureClass. */
    private int connectingWithFailureStreak;

    BluetoothStateReporter(ObdService service, SdpProbe sdpProbe) {
        this.service = service;
        this.sdpProbe = sdpProbe;
    }

    /**
     * Registers the OS-level Bluetooth receiver plus our own status receiver. Caller (the service)
     * decides whether to call this — we don't gate on permissions internally beyond skipping the
     * OS-broadcast subset that needs {@code BLUETOOTH_CONNECT}, because the status-broadcast
     * receiver doesn't need any BT permission.
     */
    void register(Context context) {
        if (context == null) {
            return;
        }
        // Both registrations swallow RuntimeException so a quirky shim path (e.g. an older API
        // level back-filling RECEIVER_NOT_EXPORTED via the auto-declared
        // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) can't take down service start. The
        // observability
        // is a "nice to have" — the engine must still come up so the user can connect.
        try {
            registerSystemReceiver(context);
        } catch (RuntimeException ex) {
            logRegisterSkipped("system", ex);
        }
        try {
            registerStatusReceiver(context);
        } catch (RuntimeException ex) {
            logRegisterSkipped("status", ex);
        }
    }

    private void logRegisterSkipped(String which, RuntimeException ex) {
        if (service != null && service.recorder != null) {
            service.recorder.logEvent(
                    "bluetooth_reporter_register_skipped",
                    "which",
                    which,
                    "reason",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    void unregister(Context context) {
        if (context == null) {
            return;
        }
        receivers.unregisterAll(context);
        activeAddress = null;
        connectingWithFailureStreak = 0;
    }

    /**
     * Pre-flight hook called from {@link ObdPollingEngine#connectAndInitialize} immediately before
     * the RFCOMM socket open. Snapshots the device/bond/adapter state. Combined A5 + B4 in a single
     * helper because both need the same {@link BluetoothDevice} resolution.
     */
    @SuppressLint("MissingPermission")
    void onPreConnect(String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        activeAddress = address;
        connectingWithFailureStreak = 0;
        if (service.recorder == null) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            service.recorder.logEvent(
                    "bluetooth_preflight", "address", address, "reason", "no_adapter");
            return;
        }
        boolean hasConnectPerm = service.hasBluetoothConnectPermission();
        boolean hasScanPerm = service.hasBluetoothScanPermission();

        // A5: adapter + device snapshot.
        String adapterState = describeAdapterState(adapter.getState());
        Boolean discovering = null;
        if (hasScanPerm) {
            try {
                discovering = adapter.isDiscovering();
            } catch (SecurityException ignored) {
                // hasScanPerm guards this, but the OS still throws when the perm is revoked
                // mid-process; null is the right "we don't know" value.
            }
        }

        BluetoothDevice device = null;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException ignored) {
            service.recorder.logEvent(
                    "bluetooth_preflight",
                    "address",
                    address,
                    "reason",
                    "invalid_address",
                    "adapterState",
                    adapterState);
            return;
        }

        String deviceName = "";
        int bondState = -1;
        int deviceType = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        ParcelUuid[] uuids = null;
        if (hasConnectPerm && device != null) {
            try {
                deviceName = safeString(device.getName());
                bondState = device.getBondState();
                deviceType = device.getType();
                uuids = device.getUuids();
            } catch (SecurityException ignored) {
                // hasConnectPerm guards these, but if the OS still revokes mid-call we just log
                // the partial snapshot.
            }
        }

        service.recorder.logEvent(
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
                discovering == null ? "unknown" : String.valueOf(discovering),
                "hasConnectPerm",
                String.valueOf(hasConnectPerm),
                "hasScanPerm",
                String.valueOf(hasScanPerm));

        // B4: bond/SDP snapshot kept as a separate event so dashboards can index it independently.
        service.recorder.logEvent(
                "bond_sdp_snapshot",
                "address",
                address,
                "bondState",
                describeBondState(bondState),
                "deviceType",
                describeDeviceType(deviceType),
                "uuids",
                uuids == null ? "[]" : Arrays.toString(uuids));
    }

    private void registerSystemReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        BroadcastReceiver systemReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handleSystemBroadcast(intent);
                    }
                };
        // Bluetooth ACTION_* intents are sent by the platform itself; use RECEIVER_EXPORTED so
        // the broadcaster (the system, not us) can deliver them on Android 14+.
        receivers.register(context, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void registerStatusReceiver(Context context) {
        IntentFilter statusFilter = new IntentFilter(ObdService.BROADCAST_STATUS);
        BroadcastReceiver statusReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handleStatusBroadcast(intent);
                    }
                };
        // Our own status broadcast is package-private (Intent.setPackage); not exported.
        receivers.register(
                context, statusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @SuppressLint("MissingPermission")
    private void handleSystemBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (service.recorder == null) {
            return;
        }
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            int previous =
                    intent.getIntExtra(
                            BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR);
            service.recorder.logEvent(
                    "bluetooth_state_changed",
                    "state",
                    describeAdapterState(state),
                    "previous",
                    describeAdapterState(previous));
            return;
        }
        // The remaining actions all carry an EXTRA_DEVICE.
        BluetoothDevice device = bluetoothDeviceExtra(intent);
        String address = device == null ? "" : safeString(device.getAddress());
        // Only log events for the device we're actually trying to talk to — keeps the log from
        // being flooded by every nearby keyboard/headset/watch on the user's phone.
        String tracking = activeAddress;
        if (tracking != null && !tracking.isEmpty() && !tracking.equalsIgnoreCase(address)) {
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            service.recorder.logEvent("bluetooth_acl_connected", "address", address);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            service.recorder.logEvent("bluetooth_acl_disconnected", "address", address);
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            int bondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            int previous =
                    intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
            service.recorder.logEvent(
                    "bluetooth_bond_state_changed",
                    "address",
                    address,
                    "bondState",
                    describeBondState(bondState),
                    "previous",
                    describeBondState(previous));
        } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
            String uuids = "[]";
            Parcelable[] raw = uuidArrayExtra(intent);
            if (raw != null) {
                uuids = Arrays.toString(raw);
            }
            service.recorder.logEvent("sdp_refresh", "address", address, "uuids", uuids);
        }
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice bluetoothDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    @SuppressWarnings("deprecation")
    private static Parcelable[] uuidArrayExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
        }
        return intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
    }

    private void handleStatusBroadcast(Intent intent) {
        String payload = intent.getStringExtra(ObdService.EXTRA_JSON);
        if (payload == null || payload.isEmpty()) {
            return;
        }
        String state;
        String failureClass;
        try {
            JSONObject json = new JSONObject(payload);
            state = json.optString("state", "");
            failureClass = json.has("failureClass") ? json.optString("failureClass") : null;
        } catch (JSONException ignored) {
            return;
        }
        boolean connecting = "connecting".equals(state);
        boolean haveFailure = failureClass != null && !failureClass.isEmpty();
        if (connecting && haveFailure) {
            connectingWithFailureStreak++;
            // Two consecutive failed connect attempts → ask the OS for fresh SDP UUIDs.
            if (connectingWithFailureStreak >= 2 && sdpProbe != null) {
                sdpProbe.trigger(activeAddress);
            }
        } else {
            // Anything that isn't a "connecting with failureClass" broadcast breaks the streak.
            // The previous version only reset on non-`connecting` states, which left stale streak
            // state across a `connecting + failure → connecting (no failureClass) → connecting +
            // failure` sequence — non-consecutive failures could fire a spurious SDP refresh.
            connectingWithFailureStreak = 0;
        }
    }

    // ---- describe helpers ----

    private static String describeAdapterState(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                return "off";
            case BluetoothAdapter.STATE_TURNING_ON:
                return "turning_on";
            case BluetoothAdapter.STATE_ON:
                return "on";
            case BluetoothAdapter.STATE_TURNING_OFF:
                return "turning_off";
            case BluetoothAdapter.ERROR:
                return "error";
            default:
                return "unknown(" + state + ")";
        }
    }

    private static String describeBondState(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:
                return "none";
            case BluetoothDevice.BOND_BONDING:
                return "bonding";
            case BluetoothDevice.BOND_BONDED:
                return "bonded";
            default:
                return "unknown(" + state + ")";
        }
    }

    private static String describeDeviceType(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                return "classic";
            case BluetoothDevice.DEVICE_TYPE_LE:
                return "le";
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                return "dual";
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN:
            default:
                return "unknown(" + type + ")";
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    /** Test-only: visibility of streak state so tests can assert SDP-refresh logic. */
    int connectingWithFailureStreakForTest() {
        return connectingWithFailureStreak;
    }

    /**
     * Test-only: pump a status payload through the handler without going through the IntentFilter.
     */
    void handleStatusForTest(String state, String failureClass) {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state);
            if (failureClass != null) {
                json.put("failureClass", failureClass);
            }
        } catch (JSONException ignored) {
            // Building from constants — this can't actually fire, but the JSON API forces a catch.
            return;
        }
        Intent intent = new Intent(ObdService.BROADCAST_STATUS);
        intent.putExtra(ObdService.EXTRA_JSON, json.toString());
        handleStatusBroadcast(intent);
    }
}
