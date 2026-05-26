package com.volttracker.obdpoc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Captures a once-per-session "what does the world look like right now" snapshot: Android version,
 * app build, Bluetooth stack state, process uptime, and the timestamp of the most recent successful
 * session from {@link SessionSummaryStore}.
 *
 * <p>Bucket 3 logs the snapshot as a {@code system_snapshot} event at session start so a future
 * diagnosis sees the device's actual state (right BT name? Android 13 vs 15? last successful
 * connect five seconds ago or five days?) without having to correlate three other log files.
 */
final class SystemSnapshot {

    private SystemSnapshot() {}

    /**
     * Builds the snapshot. Pass a {@link SessionSummaryStore} so {@code lastSuccessfulSessionMs}
     * can be filled in; pass {@code null} to omit that field (e.g. during very early startup before
     * the store is wired up).
     */
    static JSONObject collect(Context ctx, SessionSummaryStore summaryStore) {
        JSONObject o = new JSONObject();
        try {
            // -- Android version --
            o.put("androidSdk", Build.VERSION.SDK_INT);
            o.put("androidRelease", str(Build.VERSION.RELEASE));
            o.put("deviceManufacturer", str(Build.MANUFACTURER));
            o.put("deviceModel", str(Build.MODEL));

            // -- App version --
            if (ctx != null) {
                try {
                    PackageInfo pi =
                            ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                    o.put("appVersionName", str(pi.versionName));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        o.put("appVersionCode", pi.getLongVersionCode());
                    } else {
                        // Pre-P only has the int field, but the value still fits a long.
                        o.put("appVersionCode", (long) pi.versionCode);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    // Our own package not found is impossible in practice but we still don't want
                    // to escalate a missing-info case to a crash.
                }
            }

            // -- Bluetooth stack info --
            addBluetoothInfo(ctx, o);

            // -- Process uptime --
            o.put("processUptimeMs", SystemClock.elapsedRealtime());

            // -- Last successful session timestamp --
            if (summaryStore != null) {
                long lastOkMs = 0L;
                for (SessionSummary s : summaryStore.getRecent(20)) {
                    if (SessionSummary.OUTCOME_SUCCESS.equals(s.outcome) && s.endMs > lastOkMs) {
                        lastOkMs = s.endMs;
                    }
                }
                o.put("lastSuccessfulSessionMs", lastOkMs);
            }
        } catch (JSONException ignored) {
            // Local strings/ints are safe.
        }
        return o;
    }

    private static void addBluetoothInfo(Context ctx, JSONObject o) throws JSONException {
        BluetoothAdapter adapter;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ex) {
            // Some unit-test harnesses don't have a BT shadow and throw here. Treat as
            // "no adapter" and move on.
            o.put("btAdapterAvailable", false);
            o.put("btError", ex.getClass().getSimpleName());
            return;
        }
        if (adapter == null) {
            o.put("btAdapterAvailable", false);
            return;
        }
        o.put("btAdapterAvailable", true);
        // Reading getName()/getAddress() needs BLUETOOTH_CONNECT on Android 12+. If we don't
        // have it (yet), record that fact instead of throwing — it's still a useful signal in
        // the snapshot ("user hadn't granted CONNECT when the session opened").
        boolean canRead = canReadBtIdentity(ctx);
        o.put("btIdentityReadable", canRead);
        // We deliberately do not capture BluetoothAdapter.getAddress(): Android 8+ returns the
        // placeholder 02:00:00:00:00:00 unless the caller holds LOCAL_MAC_ADDRESS (a privileged
        // permission), which we don't, so the value would be useless and would also trip
        // HardwareIds lint warnings on every build. The local adapter's name is enough signal for
        // a diagnostic snapshot.
        if (canRead) {
            try {
                String name = adapter.getName();
                if (name != null) {
                    o.put("btName", name);
                }
            } catch (SecurityException ignored) {
                // Permission was revoked between our check and the call.
                o.put("btIdentityReadable", false);
            }
        }
        try {
            o.put("btEnabled", adapter.isEnabled());
        } catch (SecurityException ignored) {
            // isEnabled() is permission-free on most platforms, but be defensive.
        }
    }

    private static boolean canReadBtIdentity(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
