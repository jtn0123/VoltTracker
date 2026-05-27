package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import java.util.Arrays;

/**
 * Triggers an SDP UUID refresh on the OBD adapter device.
 *
 * <p>When a connect attempt fails on the SPP UUID, one common cause is that the device's cached SDP
 * record has gone stale (especially after a phone reboot or BT-stack restart on the adapter side).
 * Calling {@link BluetoothDevice#fetchUuidsWithSdp()} forces the OS to re-query the device's
 * service descriptors; the result arrives asynchronously as a {@link BluetoothDevice#ACTION_UUID}
 * broadcast that {@link BluetoothStateReporter} catches and logs as a {@code sdp_refresh} event.
 *
 * <p>This class is intentionally just the trigger — the response handling lives in the receiver so
 * the same broadcast path can also catch unsolicited UUID updates from the OS.
 *
 * <p>Throttling: SDP fetches over BR/EDR are expensive (~1-2s per call); only refresh once per
 * cooldown window even if the caller asks repeatedly. Tracks the last-refreshed time on the
 * instance so a long-lived service holds the cooldown across reconnect attempts.
 */
class SdpProbe {

    static final long DEFAULT_COOLDOWN_MS = 10_000L;

    private final ObdService service;
    private final long cooldownMs;
    private long lastTriggeredAtMs;

    SdpProbe(ObdService service) {
        this(service, DEFAULT_COOLDOWN_MS);
    }

    SdpProbe(ObdService service, long cooldownMs) {
        this.service = service;
        this.cooldownMs = cooldownMs;
    }

    /**
     * Schedules an SDP refresh for {@code address}. Returns {@code true} when the call was
     * dispatched, {@code false} when the request was skipped (missing permission, invalid address,
     * cooldown still active, or the adapter is unavailable). Logs the outcome either way so a
     * failed refresh is visible in the session log.
     */
    @SuppressLint("MissingPermission")
    boolean trigger(String address) {
        if (service == null || address == null || address.trim().isEmpty()) {
            return false;
        }
        if (!service.hasBluetoothConnectPermission()) {
            log("sdp_refresh_skipped", address, "reason", "missing_bluetooth_connect");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggeredAtMs < cooldownMs) {
            log(
                    "sdp_refresh_skipped",
                    address,
                    "reason",
                    "cooldown",
                    "sinceLastMs",
                    String.valueOf(now - lastTriggeredAtMs));
            return false;
        }
        BluetoothAdapter adapter = BluetoothAdapters.get(service);
        if (adapter == null) {
            log("sdp_refresh_skipped", address, "reason", "no_adapter");
            return false;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException ex) {
            log("sdp_refresh_skipped", address, "reason", "invalid_address");
            return false;
        }
        boolean dispatched;
        try {
            dispatched = device.fetchUuidsWithSdp();
        } catch (SecurityException ex) {
            log("sdp_refresh_skipped", address, "reason", "security_exception");
            return false;
        }
        lastTriggeredAtMs = now;
        log(
                "sdp_refresh_requested",
                address,
                "dispatched",
                String.valueOf(dispatched),
                "cachedUuids",
                safeUuids(device));
        return dispatched;
    }

    @SuppressLint("MissingPermission")
    private String safeUuids(BluetoothDevice device) {
        if (!service.hasBluetoothConnectPermission()) {
            return "";
        }
        try {
            ParcelUuid[] uuids = device.getUuids();
            return uuids == null ? "[]" : Arrays.toString(uuids);
        } catch (SecurityException ex) {
            return "";
        }
    }

    private void log(String event, String address, String... extras) {
        if (service.recorder == null) {
            return;
        }
        String[] pairs = new String[2 + extras.length];
        pairs[0] = "address";
        pairs[1] = address;
        System.arraycopy(extras, 0, pairs, 2, extras.length);
        service.recorder.logEvent(event, pairs);
    }
}
