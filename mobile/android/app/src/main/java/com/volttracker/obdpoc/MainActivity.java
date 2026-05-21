package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String TAG = "VoltTracker";
    private static final String PREFS = "volt_obd_prefs";
    private WebView webView;
    private boolean pageReady;
    private SharedPreferences prefs;
    DeviceCatalog deviceCatalog;
    DataBackup dataBackup;
    BackupController backupController;
    PermissionGate permissionGate;
    ObdLocalStore localStore;
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
        deviceCatalog = new DeviceCatalog(this, prefs);
        dataBackup = new DataBackup(this);
        backupController = new BackupController(this, dataBackup, backgroundExecutor);
        permissionGate = new PermissionGate(this);
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
        webView.addJavascriptInterface(new VoltBridge(this), "VoltTrackerAndroid");
        webView.loadUrl("file:///android_asset/dashboard/index.html");

        permissionGate.ensureGranted();
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
        if (requestCode == PermissionGate.REQUEST_CODE) {
            publishDeviceList();
            if (deviceCatalog.hasBluetoothConnectPermission()) {
                publishStatus("ready", "Bluetooth permission granted. Pick a paired adapter.", false);
            } else {
                publishStatus("blocked", "Bluetooth permission is required to talk to the OBD adapter.", true);
            }
        }
    }

    void publishDeviceList() {
        callDashboard("window.VoltTrackerNative.setDevices("
                + JSONObject.quote(deviceCatalog.getBondedDevicesJson()) + ")");
        callDashboard("window.VoltTrackerNative.setHistory("
                + JSONObject.quote(deviceCatalog.getDeviceHistoryJson()) + ")");
    }

    void publishStatus(String state, String detail, boolean blocked) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("bluetoothReady", isBluetoothReady());
            payload.put("lastAddress", deviceCatalog.lastAddress());
            payload.put("lastName", deviceCatalog.lastName());
        } catch (JSONException ignored) {
            // Values are local literals.
        }
        callDashboard("window.VoltTrackerNative.setStatus(" + JSONObject.quote(payload.toString()) + ")");
        lastStatus = payload;
        publishAppState();
    }

    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled() && deviceCatalog.hasBluetoothConnectPermission();
    }

    @SuppressLint("MissingPermission")
    void startObdService(String action, String address, String name) {
        if (!permissionGate.ensureGranted()) {
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

    void rememberDevice(String address, String name) {
        String cleanAddress = deviceCatalog.remember(address, name);
        if (cleanAddress.isEmpty()) {
            return;
        }
        String cleanName = name == null ? "" : name.trim();
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

    void stopObdService() {
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

    void publishStorageSummary() {
        // getStorageSummary runs many queries over a large DB; keep it off the UI thread.
        backgroundExecutor.execute(() -> {
            final String storage = getStorageSummaryJson();
            runOnUiThread(() -> {
                lastStorage = parseJson(storage);
                callDashboard("window.VoltTrackerNative.setStorage(" + JSONObject.quote(storage) + ")");
            });
        });
    }

    String getStorageSummaryJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getStorageSummary().toString();
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    String getTripsJson() {
        if (localStore == null) {
            return "[]";
        }
        try {
            return localStore.getTripsJson(40).toString();
        } catch (RuntimeException ex) {
            return "[]";
        }
    }

    String getInsightsJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getInsightsJson().toString();
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        backupController.onActivityResult(requestCode, resultCode, data);
    }

    /** True while an OBD logging session is connecting or active. */
    boolean isLoggingActive() {
        return isConnectedState(lastStatus == null ? "" : lastStatus.optString("state", ""));
    }

    private void publishAppState() {
        callDashboard("window.VoltTrackerNative.setAppState(" + JSONObject.quote(getAppStateJson()) + ")");
    }

    String getAppStateJson() {
        return AppStateJson.build(
                appVersionName(),
                isBluetoothReady(),
                permissionGate.hasLocation(),
                permissionGate.hasNotifications(),
                deviceCatalog.lastAddress(),
                deviceCatalog.lastName(),
                lastTelemetry,
                lastStatus,
                lastStorage);
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

}
