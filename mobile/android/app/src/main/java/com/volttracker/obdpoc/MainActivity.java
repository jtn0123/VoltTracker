package com.volttracker.obdpoc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 4101;
    private static final String PREFS = "volt_obd_prefs";
    private static final String PREF_LAST_ADDRESS = "last_address";
    private static final String PREF_LAST_NAME = "last_name";
    private static final String PREF_DEVICE_HISTORY = "device_history";
    private static final int MAX_DEVICE_HISTORY = 8;
    private WebView webView;
    private boolean pageReady;
    private SharedPreferences prefs;

    private final BroadcastReceiver obdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String json = intent.getStringExtra(ObdService.EXTRA_JSON);
            if (json == null) {
                json = "{}";
            }
            if (ObdService.BROADCAST_TELEMETRY.equals(action)) {
                callDashboard("window.VoltTrackerNative.updateTelemetry(" + JSONObject.quote(json) + ")");
            } else if (ObdService.BROADCAST_STATUS.equals(action)) {
                callDashboard("window.VoltTrackerNative.setStatus(" + JSONObject.quote(json) + ")");
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                publishDeviceList();
                publishStatus("ready", "Pick a paired OBD adapter or run demo mode.", false);
            }
        });
        webView.addJavascriptInterface(new VoltBridge(), "VoltTrackerAndroid");
        webView.loadUrl("file:///android_asset/dashboard/index.html");

        ensurePermissions();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ObdService.BROADCAST_TELEMETRY);
        filter.addAction(ObdService.BROADCAST_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(obdReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(obdReceiver, filter);
        }
        publishDeviceList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(obdReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver can already be unregistered if Android tears down the Activity quickly.
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            publishDeviceList();
            if (hasBluetoothConnectPermission()) {
                publishStatus("ready", "Bluetooth permission granted. Pick a paired adapter.", false);
            } else {
                publishStatus("blocked", "Bluetooth permission is required to talk to the OBD adapter.", true);
            }
        }
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return false;
        }
        return true;
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private String getBondedDevicesJson() {
        JSONArray devices = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return devices.toString();
        }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> sorted = new ArrayList<>(bonded);
        Collections.sort(sorted, (left, right) -> {
            int candidateSort = Boolean.compare(isLikelyObdDevice(right), isLikelyObdDevice(left));
            if (candidateSort != 0) {
                return candidateSort;
            }
            return safeName(left).toLowerCase(Locale.US).compareTo(safeName(right).toLowerCase(Locale.US));
        });

        for (BluetoothDevice device : sorted) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", safeName(device));
                item.put("address", device.getAddress());
                item.put("type", device.getType());
                item.put("bondState", device.getBondState());
                item.put("obdCandidate", isLikelyObdDevice(device));
                devices.put(item);
            } catch (JSONException ignored) {
                // Skip malformed device entries. Android-provided addresses should be valid.
            }
        }
        return devices.toString();
    }

    @SuppressLint("MissingPermission")
    private JSONArray getLikelyObdCandidates() {
        JSONArray candidates = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return candidates;
        }

        List<BluetoothDevice> sorted = new ArrayList<>(adapter.getBondedDevices());
        Collections.sort(sorted, (left, right) ->
                safeName(left).toLowerCase(Locale.US).compareTo(safeName(right).toLowerCase(Locale.US)));
        for (BluetoothDevice device : sorted) {
            if (!isLikelyObdDevice(device)) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("address", device.getAddress());
                item.put("name", safeName(device));
                item.put("lastSeen", 0);
                item.put("connectCount", 0);
                item.put("candidate", true);
                candidates.put(item);
            } catch (JSONException ignored) {
                // Skip malformed device entries.
            }
        }
        return candidates;
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice device) {
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            return "OBD adapter";
        }
        return name.trim();
    }

    @SuppressLint("MissingPermission")
    private static boolean isLikelyObdDevice(BluetoothDevice device) {
        return isLikelyObdName(safeName(device));
    }

    private static boolean isLikelyObdName(String name) {
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

    private void publishDeviceList() {
        callDashboard("window.VoltTrackerNative.setDevices(" + JSONObject.quote(getBondedDevicesJson()) + ")");
        callDashboard("window.VoltTrackerNative.setHistory(" + JSONObject.quote(getDeviceHistoryJson()) + ")");
    }

    private void publishStatus(String state, String detail, boolean blocked) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("bluetoothReady", isBluetoothReady());
            payload.put("lastAddress", prefs.getString(PREF_LAST_ADDRESS, ""));
            payload.put("lastName", prefs.getString(PREF_LAST_NAME, ""));
        } catch (JSONException ignored) {
            // Values are local literals.
        }
        callDashboard("window.VoltTrackerNative.setStatus(" + JSONObject.quote(payload.toString()) + ")");
    }

    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled() && hasBluetoothConnectPermission();
    }

    @SuppressLint("MissingPermission")
    private void startObdService(String action, String address, String name) {
        if (!ensurePermissions()) {
            publishStatus("blocked", "Grant Bluetooth permission, then connect again.", true);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            publishStatus("blocked", "This phone does not report Bluetooth support.", true);
            return;
        }
        if (!adapter.isEnabled()) {
            publishStatus("blocked", "Turn on Bluetooth to connect to the OBD adapter.", true);
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
            return;
        }

        Intent service = new Intent(this, ObdService.class);
        service.setAction(action);
        if (address != null) {
            service.putExtra(ObdService.EXTRA_ADDRESS, address);
        }
        if (name != null) {
            service.putExtra(ObdService.EXTRA_NAME, name);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void rememberDevice(String address, String name) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        String cleanAddress = address.trim();
        String cleanName = name == null ? "" : name.trim();
        JSONArray history = updatedDeviceHistory(cleanAddress, cleanName);
        prefs.edit()
                .putString(PREF_LAST_ADDRESS, cleanAddress)
                .putString(PREF_LAST_NAME, cleanName)
                .putString(PREF_DEVICE_HISTORY, history.toString())
                .apply();
        publishDeviceList();
        publishStatus("ready", "Remembered " + (cleanName.isEmpty() ? cleanAddress : cleanName) + ".", false);
    }

    private String getLastDeviceJson() {
        return getLastOrCandidateDevice().toString();
    }

    private JSONObject getLastOrCandidateDevice() {
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

    private String getDeviceHistoryJson() {
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

    private void stopObdService() {
        Intent service = new Intent(this, ObdService.class);
        service.setAction(ObdService.ACTION_DISCONNECT);
        startService(service);
    }

    private void callDashboard(String script) {
        if (!pageReady || webView == null) {
            return;
        }
        runOnUiThread(() -> webView.evaluateJavascript(script + ";", null));
    }

    public final class VoltBridge {
        @JavascriptInterface
        public String listDevices() {
            return getBondedDevicesJson();
        }

        @JavascriptInterface
        public void requestPermissions() {
            runOnUiThread(MainActivity.this::ensurePermissions);
        }

        @JavascriptInterface
        public void refreshDevices() {
            runOnUiThread(MainActivity.this::publishDeviceList);
        }

        @JavascriptInterface
        public void connect(String address, String name) {
            runOnUiThread(() -> {
                rememberDevice(address, name);
                startObdService(ObdService.ACTION_CONNECT, address, name);
            });
        }

        @JavascriptInterface
        public void scan(String address, String name) {
            runOnUiThread(() -> {
                rememberDevice(address, name);
                startObdService(ObdService.ACTION_SCAN, address, name);
            });
        }

        @JavascriptInterface
        public String getLastDevice() {
            return getLastDeviceJson();
        }

        @JavascriptInterface
        public String getDeviceHistory() {
            return getDeviceHistoryJson();
        }

        @JavascriptInterface
        public void rememberDevice(String address, String name) {
            runOnUiThread(() -> MainActivity.this.rememberDevice(address, name));
        }

        @JavascriptInterface
        public void connectLast() {
            JSONObject device = getLastOrCandidateDevice();
            String address = device.optString("address", "");
            String name = device.optString("name", "");
            runOnUiThread(() -> {
                if (address == null || address.trim().isEmpty()) {
                    publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true);
                    return;
                }
                rememberDevice(address, name);
                startObdService(ObdService.ACTION_CONNECT, address, name);
            });
        }

        @JavascriptInterface
        public void scanLast() {
            JSONObject device = getLastOrCandidateDevice();
            String address = device.optString("address", "");
            String name = device.optString("name", "");
            runOnUiThread(() -> {
                if (address == null || address.trim().isEmpty()) {
                    publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true);
                    return;
                }
                rememberDevice(address, name);
                startObdService(ObdService.ACTION_SCAN, address, name);
            });
        }

        @JavascriptInterface
        public void demo() {
            runOnUiThread(() -> startObdService(ObdService.ACTION_DEMO, null, "Demo stream"));
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(MainActivity.this::stopObdService);
        }
    }
}
