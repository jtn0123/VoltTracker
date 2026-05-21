package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;

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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "VoltTracker";
    private static final int REQUEST_PERMISSIONS = 4101;
    private static final int REQUEST_RESTORE = 4202;
    private static final String PREFS = "volt_obd_prefs";
    private static final String PREF_LAST_ADDRESS = "last_address";
    private static final String PREF_LAST_NAME = "last_name";
    private static final String PREF_DEVICE_HISTORY = "device_history";
    private static final int MAX_DEVICE_HISTORY = 8;
    private WebView webView;
    private boolean pageReady;
    private SharedPreferences prefs;
    private ObdLocalStore localStore;
    private JSONObject lastTelemetry = new JSONObject();
    private JSONObject lastStatus = new JSONObject();
    private JSONObject lastStorage = new JSONObject();
    // Off-UI-thread worker for the heavy storage-summary query.
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver obdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String json = intent.getStringExtra(ObdService.EXTRA_JSON);
            if (json == null) {
                json = "{}";
            }
            if (ObdService.BROADCAST_TELEMETRY.equals(action)) {
                lastTelemetry = parseJson(json);
                callDashboard("window.VoltTrackerNative.updateTelemetry(" + JSONObject.quote(json) + ")");
                publishAppState();
            } else if (ObdService.BROADCAST_STATUS.equals(action)) {
                lastStatus = parseJson(json);
                callDashboard("window.VoltTrackerNative.setStatus(" + JSONObject.quote(json) + ")");
                publishStorageSummary();
                publishAppState();
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        localStore = new ObdLocalStore(this);
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
        settings.setTextZoom(100);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "dashboard console: " + message.message()
                            + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                publishDeviceList();
                publishStorageSummary();
                publishAppState();
                publishStatus("ready", "Pick a paired OBD adapter to start logging.", false);
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
        publishStorageSummary();
        publishAppState();
        reportAppVisibility(true);
    }

    @Override
    protected void onPause() {
        reportAppVisibility(false);
        super.onPause();
        try {
            unregisterReceiver(obdReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver can already be unregistered if Android tears down the Activity quickly.
        }
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        if (localStore != null) {
            localStore.close();
            localStore = null;
        }
        super.onDestroy();
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
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
        lastStatus = payload;
        publishAppState();
    }

    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled() && hasBluetoothConnectPermission();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
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
        try {
            if (localStore != null) {
                localStore.recordAdapterSummary(
                        cleanAddress,
                        cleanName,
                        ObdLocalStore.MODE_OBD,
                        0L,
                        "remembered",
                        0,
                        "",
                        "Remembered adapter"
                );
            }
        } catch (RuntimeException ignored) {
            // Preferences remain the source of truth for adapter recall if DB storage fails.
        }
        publishDeviceList();
        publishStorageSummary();
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

    private void reportAppVisibility(boolean foreground) {
        Intent service = new Intent(this, ObdService.class);
        service.setAction(foreground ? ObdService.ACTION_APP_FOREGROUND : ObdService.ACTION_APP_BACKGROUND);
        try {
            startService(service);
        } catch (IllegalStateException ignored) {
            // Visibility is diagnostic only; do not interrupt the Activity lifecycle.
        }
    }

    private void callDashboard(String script) {
        if (!pageReady || webView == null) {
            return;
        }
        runOnUiThread(() -> webView.evaluateJavascript(script + ";", null));
    }

    private void publishStorageSummary() {
        // getStorageSummary runs many queries over a large DB; keep it off the UI thread.
        backgroundExecutor.execute(() -> {
            final String storage = getStorageSummaryJson();
            runOnUiThread(() -> {
                lastStorage = parseJson(storage);
                callDashboard("window.VoltTrackerNative.setStorage(" + JSONObject.quote(storage) + ")");
            });
        });
    }

    private String getStorageSummaryJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getStorageSummary().toString();
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    private String getTripsJson() {
        if (localStore == null) {
            return "[]";
        }
        try {
            return localStore.getTripsJson(40).toString();
        } catch (RuntimeException ex) {
            return "[]";
        }
    }

    private String getInsightsJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getInsightsJson().toString();
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    private String exportDebugBundleJson() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("createdAtMs", System.currentTimeMillis());
            payload.put("appState", parseJson(getAppStateJson()));
            payload.put("storage", parseJson(getStorageSummaryJson()));
            File dir = new File(getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                payload.put("ok", false);
                payload.put("error", "Could not create export directory.");
                return payload.toString();
            }
            File file = new File(dir, "volttracker-debug-summary-" + System.currentTimeMillis() + ".json");
            payload.put("ok", true);
            payload.put("path", file.getAbsolutePath());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(payload.toString(2));
            }
        } catch (JSONException | IOException ex) {
            try {
                payload.put("ok", false);
                payload.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (JSONException ignored) {
                // Local strings are safe.
            }
        }
        return payload.toString();
    }

    // Produces a complete on-device data backup and hands it to the Android share sheet
    // so the user can save it anywhere (cloud, PC) — no server involved.
    private void launchBackupShare() {
        publishStatus("ready", "Preparing data backup...", false);
        backgroundExecutor.execute(() -> {
            final File backup = buildBackupFile();
            runOnUiThread(() -> {
                if (backup == null) {
                    publishStatus("blocked", "Could not create the backup file.", true);
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", backup);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/octet-stream");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.putExtra(Intent.EXTRA_SUBJECT, backup.getName());
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Back up Volt Tracker data"));
                    publishStatus("ready", "Backup ready - choose where to save it.", false);
                } catch (RuntimeException ex) {
                    publishStatus("blocked", "Could not open the share sheet.", true);
                }
            });
        });
    }

    private File buildBackupFile() {
        if (localStore == null) {
            return null;
        }
        try {
            localStore.checkpoint();
            File source = localStore.getDatabaseFile();
            if (source == null || !source.exists()) {
                return null;
            }
            File dir = new File(getCacheDir(), "backups");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            clearOldBackups(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File dest = new File(dir, "volttracker-backup-" + stamp + ".db");
            copyFile(source, dest);
            return dest;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static void clearOldBackups(File dir) {
        File[] existing = dir.listFiles();
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            // Backups are transient hand-off copies; keep only the freshest one.
            file.delete();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RESTORE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            restoreFromUri(data.getData());
        }
    }

    private void launchRestorePicker() {
        // Refuse restore while a logging session is active so the swap cannot race
        // in-flight ObdService database writes.
        String state = lastStatus == null ? "" : lastStatus.optString("state", "");
        if (isConnectedState(state)) {
            publishStatus("blocked", "Stop logging before restoring a backup.", true);
            return;
        }
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQUEST_RESTORE);
        } catch (RuntimeException ex) {
            publishStatus("blocked", "Could not open the file picker.", true);
        }
    }

    private void restoreFromUri(Uri uri) {
        publishStatus("ready", "Restoring backup...", false);
        backgroundExecutor.execute(() -> {
            final boolean ok = applyRestore(uri);
            runOnUiThread(() -> {
                if (ok) {
                    publishDeviceList();
                    publishStorageSummary();
                    publishStatus("ready", "Backup restored - reconnect to resume logging.", false);
                } else {
                    publishStatus("blocked",
                            "Restore failed - that file is not a valid Volt Tracker backup.", true);
                }
            });
        });
    }

    // Replaces the on-device database with a user-picked backup file. The file is copied
    // out and verified as SQLite before the live database is touched.
    private boolean applyRestore(Uri uri) {
        File temp = new File(getCacheDir(), "restore-tmp.db");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) {
                    return false;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            if (!isVoltTrackerBackup(temp)) {
                return false;
            }
            File dbFile = localStore == null ? null : localStore.getDatabaseFile();
            if (dbFile == null) {
                return false;
            }
            // Stop any logging session so the database file is not held open.
            stopObdServiceForRestore();
            if (localStore != null) {
                localStore.close();
                localStore = null;
            }
            copyFile(temp, dbFile);
            deleteIfExists(new File(dbFile.getPath() + "-wal"));
            deleteIfExists(new File(dbFile.getPath() + "-shm"));
            localStore = new ObdLocalStore(this);
            return true;
        } catch (IOException | RuntimeException ex) {
            if (localStore == null) {
                try {
                    localStore = new ObdLocalStore(this);
                } catch (RuntimeException ignored) {
                    // Nothing more we can do; the next launch will recreate it.
                }
            }
            return false;
        } finally {
            temp.delete();
        }
    }

    private void stopObdServiceForRestore() {
        try {
            Intent stop = new Intent(this, ObdService.class);
            stop.setAction(ObdService.ACTION_DISCONNECT);
            startService(stop);
        } catch (RuntimeException ignored) {
            // Best effort; restore proceeds regardless.
        }
    }

    // Confirms a restore file is a real Volt Tracker database: a SQLite file that
    // contains the app's core tables. A plain SQLite file with a foreign schema would
    // leave the app's queries broken after the swap.
    private static boolean isVoltTrackerBackup(File file) {
        byte[] header = new byte[16];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length
                    || !new String(header, StandardCharsets.US_ASCII).startsWith("SQLite format 3")) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor cursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
                    new String[]{"obd_sessions", "telemetry_samples"})) {
                return cursor.getCount() == 2;
            }
        } catch (RuntimeException ex) {
            return false;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private void publishAppState() {
        callDashboard("window.VoltTrackerNative.setAppState(" + JSONObject.quote(getAppStateJson()) + ")");
    }

    private String getAppStateJson() {
        JSONObject payload = new JSONObject();
        try {
            JSONObject app = new JSONObject();
            app.put("version", appVersionName());
            app.put("schemaVersion", 4);
            payload.put("app", app);

            JSONObject permissions = new JSONObject();
            permissions.put("bluetooth", isBluetoothReady());
            permissions.put("location", hasLocationPermission());
            permissions.put("notifications", hasNotificationPermission());
            payload.put("permissions", permissions);

            JSONObject adapter = new JSONObject();
            String lastAddress = prefs.getString(PREF_LAST_ADDRESS, "");
            String lastName = prefs.getString(PREF_LAST_NAME, "");
            adapter.put("name", coalesce(lastTelemetry.optString("adapter", ""), lastStatus.optString("adapter", ""), lastName));
            adapter.put("address", redactAddress(lastAddress));
            adapter.put("remembered", lastAddress != null && !lastAddress.trim().isEmpty());
            adapter.put("connected", isConnectedState(lastStatus.optString("state", "")));
            payload.put("adapter", adapter);

            JSONObject session = new JSONObject();
            session.put("mode", lastTelemetry.optString("source", ""));
            session.put("state", lastStatus.optString("state", "idle"));
            session.put("detail", lastStatus.optString("detail", ""));
            session.put("sampleCount", lastTelemetry.optInt("sampleCount", 0));
            session.put("sessionMs", lastTelemetry.optLong("sessionMs", 0L));
            session.put("backgroundSampleCount", lastTelemetry.optInt("backgroundSampleCount", 0));
            session.put("sampleGapCount", lastTelemetry.optInt("sampleGapCount", 0));
            session.put("maxSampleGapMs", lastTelemetry.optLong("maxSampleGapMs", 0L));
            payload.put("session", session);

            JSONObject vehicle = new JSONObject();
            vehicle.put("state", lastTelemetry.optString("vehicleState", "unknown"));
            vehicle.put("confidence", lastTelemetry.optString("vehicleStateConfidence",
                    lastTelemetry.has("vehicleState") ? "observed" : "unknown"));
            vehicle.put("vinStored", false);
            payload.put("vehicle", vehicle);

            JSONObject gps = new JSONObject();
            boolean hasLocation = lastTelemetry.has("latitude") && lastTelemetry.has("longitude");
            gps.put("state", hasLocation ? "locked" : (hasLocationPermission() ? "waiting" : "blocked"));
            if (lastTelemetry.has("accuracyM")) {
                gps.put("accuracyM", lastTelemetry.optDouble("accuracyM"));
            }
            if (lastTelemetry.has("locationAgeMs")) {
                gps.put("ageMs", lastTelemetry.optLong("locationAgeMs"));
            }
            payload.put("gps", gps);

            JSONObject lifecycle = new JSONObject();
            lifecycle.put("appForeground", lastTelemetry.optBoolean("appForeground", true));
            lifecycle.put("foregroundServiceActive", lastTelemetry.optBoolean("foregroundServiceActive", false));
            lifecycle.put("backgroundSampleCount", lastTelemetry.optInt("backgroundSampleCount", 0));
            lifecycle.put("sampleGapCount", lastTelemetry.optInt("sampleGapCount", 0));
            lifecycle.put("lastSampleGapMs", lastTelemetry.optLong("lastSampleGapMs", 0L));
            lifecycle.put("maxSampleGapMs", lastTelemetry.optLong("maxSampleGapMs", 0L));
            payload.put("lifecycle", lifecycle);

            payload.put("latestTelemetry", lastTelemetry);
            payload.put("storage", lastStorage);
        } catch (JSONException ignored) {
            // Local state values are safe.
        }
        return payload.toString();
    }

    static JSONObject parseJson(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    static boolean isConnectedState(String state) {
        String clean = state == null ? "" : state.toLowerCase(Locale.US);
        return clean.equals("connected")
                || clean.equals("connecting")
                || clean.equals("initializing")
                || clean.equals("scanning")
                || clean.equals("scan-complete")
                || clean.equals("demo");
    }

    static String coalesce(String first, String second, String third) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        return third == null ? "" : third;
    }

    static String redactAddress(String address) {
        if (address == null || address.length() < 5) {
            return "";
        }
        return "..." + address.substring(address.length() - 5);
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "";
        }
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
            runOnUiThread(() -> {
                publishDeviceList();
                publishStorageSummary();
            });
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
        public String getStorageSummary() {
            return getStorageSummaryJson();
        }

        @JavascriptInterface
        public String exportDebugBundle() {
            return exportDebugBundleJson();
        }

        @JavascriptInterface
        public void shareBackup() {
            runOnUiThread(MainActivity.this::launchBackupShare);
        }

        @JavascriptInterface
        public void restoreBackup() {
            runOnUiThread(MainActivity.this::launchRestorePicker);
        }

        @JavascriptInterface
        public String getTrips() {
            return getTripsJson();
        }

        @JavascriptInterface
        public String getInsights() {
            return getInsightsJson();
        }

        @JavascriptInterface
        public void clearStoredData() {
            runOnUiThread(() -> {
                try {
                    if (localStore != null) {
                        localStore.clearAllData();
                    }
                } catch (RuntimeException ignored) {
                    publishStatus("blocked", "Could not clear the local OBD database.", true);
                    return;
                }
                publishStorageSummary();
                publishStatus("ready", "On-phone OBD database cleared.", false);
            });
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

        @JavascriptInterface
        public void logClientError(String label, String detail) {
            Log.e(TAG, "dashboard client error [" + label + "]: " + detail);
        }
    }
}
