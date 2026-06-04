package com.volttracker.obdpoc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns paired-adapter discovery, OBD-likelihood heuristics, and the remembered-device history
 * persisted in {@link SharedPreferences}. Extracted from {@link MainActivity} so the
 * Bluetooth/preferences plumbing is isolated and the name heuristics stay unit-testable.
 */
final class DeviceCatalog {

    static final String PREF_LAST_ADDRESS = "last_address";
    static final String PREF_LAST_NAME = "last_name";
    private static final String PREF_DEVICE_HISTORY = "device_history";
    private static final int MAX_DEVICE_HISTORY = 8;

    private final Context context;
    private final SharedPreferences prefs;

    DeviceCatalog(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    String lastAddress() {
        return prefs.getString(PREF_LAST_ADDRESS, "");
    }

    String lastName() {
        return prefs.getString(PREF_LAST_NAME, "");
    }

    boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    String getBondedDevicesJson() {
        JSONArray devices = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapters.get(context);
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return devices.toString();
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException ex) {
            return devices.toString();
        }
        List<BluetoothDevice> sorted = new ArrayList<>(bonded);
        Collections.sort(
                sorted,
                (left, right) -> {
                    int candidateSort =
                            Boolean.compare(isLikelyObdDevice(right), isLikelyObdDevice(left));
                    if (candidateSort != 0) {
                        return candidateSort;
                    }
                    return safeName(left)
                            .toLowerCase(Locale.US)
                            .compareTo(safeName(right).toLowerCase(Locale.US));
                });

        for (BluetoothDevice device : sorted) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", safeName(device));
                item.put("address", safeAddress(device));
                item.put("type", safeType(device));
                item.put("bondState", safeBondState(device));
                item.put("obdCandidate", isLikelyObdDevice(device));
                devices.put(item);
            } catch (JSONException | SecurityException ignored) {
                // Skip malformed device entries. Android-provided addresses should be valid.
            }
        }
        return devices.toString();
    }

    @SuppressLint("MissingPermission")
    private JSONArray getLikelyObdCandidates() {
        JSONArray candidates = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapters.get(context);
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return candidates;
        }

        List<BluetoothDevice> sorted;
        try {
            sorted = new ArrayList<>(adapter.getBondedDevices());
        } catch (SecurityException ex) {
            return candidates;
        }
        Collections.sort(
                sorted,
                (left, right) ->
                        safeName(left)
                                .toLowerCase(Locale.US)
                                .compareTo(safeName(right).toLowerCase(Locale.US)));
        for (BluetoothDevice device : sorted) {
            if (!isLikelyObdDevice(device)) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("address", safeAddress(device));
                item.put("name", safeName(device));
                item.put("lastSeen", 0);
                item.put("connectCount", 0);
                item.put("candidate", true);
                candidates.put(item);
            } catch (JSONException | SecurityException ignored) {
                // Skip malformed device entries.
            }
        }
        return candidates;
    }

    @SuppressLint("MissingPermission")
    private static String safeAddress(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (SecurityException ex) {
            return "";
        }
    }

    @SuppressLint("MissingPermission")
    private static int safeType(BluetoothDevice device) {
        try {
            return device.getType();
        } catch (SecurityException ex) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    @SuppressLint("MissingPermission")
    private static int safeBondState(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (SecurityException ex) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
        } catch (SecurityException ex) {
            return "OBD adapter";
        }
        if (name == null || name.trim().isEmpty()) {
            return "OBD adapter";
        }
        return name.trim();
    }

    @SuppressLint("MissingPermission")
    private static boolean isLikelyObdDevice(BluetoothDevice device) {
        return isLikelyObdName(safeName(device));
    }

    static boolean isLikelyObdName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.contains("obd")
                || lower.contains("elm")
                || lower.contains("vlink")
                || lower.contains("veepeak")
                || lower.contains("obdlink")
                || lower.contains("mx+")
                || lower.contains("carista")
                || lower.contains("scanner");
    }

    /**
     * Persists {@code address}/{@code name} as the last adapter and folds it into the
     * remembered-device history. Returns the trimmed address, or {@code ""} when blank.
     */
    String remember(String address, String name) {
        if (address == null || address.trim().isEmpty()) {
            return "";
        }
        String cleanAddress = address.trim();
        if (!BluetoothAdapter.checkBluetoothAddress(cleanAddress)) {
            return "";
        }
        String cleanName = name == null ? "" : name.trim();
        JSONArray history = updatedDeviceHistory(cleanAddress, cleanName);
        prefs.edit()
                .putString(PREF_LAST_ADDRESS, cleanAddress)
                .putString(PREF_LAST_NAME, cleanName)
                .putString(PREF_DEVICE_HISTORY, history.toString())
                .apply();
        return cleanAddress;
    }

    String getLastDeviceJson() {
        return getLastOrCandidateDevice().toString();
    }

    JSONObject getLastOrCandidateDevice() {
        JSONObject payload = new JSONObject();
        String address = prefs.getString(PREF_LAST_ADDRESS, "");
        String name = prefs.getString(PREF_LAST_NAME, "");
        if ((address == null || address.trim().isEmpty())) {
            JSONObject candidate = getLikelyObdCandidates().optJSONObject(0);
            if (candidate != null) {
                return candidate;
            }
        }
        try {
            payload.put("address", address == null ? "" : address);
            payload.put("name", name == null ? "" : name);
        } catch (JSONException ignored) {
            // Preference strings are local.
        }
        return payload;
    }

    String getDeviceHistoryJson() {
        JSONArray history = parseDeviceHistory();
        if (history.length() == 0) {
            String address = prefs.getString(PREF_LAST_ADDRESS, "");
            if (address != null && !address.trim().isEmpty()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("address", address.trim());
                    item.put("name", prefs.getString(PREF_LAST_NAME, ""));
                    item.put("lastSeen", System.currentTimeMillis());
                    item.put("connectCount", 1);
                    history.put(item);
                } catch (JSONException ignored) {
                    // Preference strings are local.
                }
            } else {
                history = getLikelyObdCandidates();
            }
        }
        return history.toString();
    }

    private JSONArray parseDeviceHistory() {
        String stored = prefs.getString(PREF_DEVICE_HISTORY, "[]");
        try {
            return new JSONArray(stored);
        } catch (JSONException ex) {
            return new JSONArray();
        }
    }

    private JSONArray updatedDeviceHistory(String address, String name) {
        JSONArray current = parseDeviceHistory();
        JSONArray next = new JSONArray();
        JSONObject remembered = new JSONObject();
        long now = System.currentTimeMillis();
        int connectCount = 1;
        long firstSeen = now;

        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (address.equalsIgnoreCase(item.optString("address", ""))) {
                connectCount = item.optInt("connectCount", 0) + 1;
                firstSeen = item.optLong("firstSeen", now);
                if (name.isEmpty()) {
                    name = item.optString("name", "");
                }
            }
        }

        try {
            remembered.put("address", address);
            remembered.put("name", name);
            remembered.put("firstSeen", firstSeen);
            remembered.put("lastSeen", now);
            remembered.put("connectCount", connectCount);
            next.put(remembered);

            for (int i = 0; i < current.length() && next.length() < MAX_DEVICE_HISTORY; i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null || address.equalsIgnoreCase(item.optString("address", ""))) {
                    continue;
                }
                next.put(item);
            }
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        return next;
    }
}
