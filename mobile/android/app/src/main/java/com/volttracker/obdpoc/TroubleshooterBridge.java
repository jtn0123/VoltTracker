package com.volttracker.obdpoc;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A1: extracted from {@link MainActivity} (lines 323-612 of the round-5 layout) so the Activity no
 * longer owns the troubleshooter / notify-when-ready bridge state directly. Each public method here
 * corresponds 1:1 to one of {@link VoltBridge}'s Bucket 4a / 4b entry points; {@link MainActivity}
 * retains thin delegates so the bridge API didn't move.
 *
 * <p>Holds an {@link MainActivity} reference for the Activity-bound calls it still needs ({@code
 * startActivity}, {@code startService}, {@code getSystemService}, {@code runOnUiThread}, {@code
 * publishStatus}, {@code rememberDevice}). This is no worse than the previous design (which had all
 * of this inlined on the Activity), and it lets every helper move out as one coherent object with
 * its own lifecycle ({@link #shutdown}) instead of being individual fields sprinkled across the
 * Activity body.
 */
final class TroubleshooterBridge {

    /**
     * How long a single probe is allowed to run before the bridge auto-stops it.
     *
     * <p>{@code ObdPollingEngine.initializeElm327()} cumulatively budgets up to ~22 s in the worst
     * case (ATZ + 7 short AT commands + two {@code 0100} attempts each with a 9 s timeout for the
     * "no prompt" recovery path). An 8 s deadline used to tear the probe down mid-init, so the
     * subsequent "connected" broadcast that {@link #onAdapterStatusForReadyNotify} listens for
     * never arrived, and the C10 notify-when-ready schedule could not fire. 25 s gives the engine
     * room to land on either "connected" or a real failure before we cut the session.
     */
    private static final long TEST_CONNECTION_DURATION_MS = 25_000L;

    /** Interval between repeated test-connection probes inside the notify-when-ready window. */
    private static final long ADAPTER_READY_INTERVAL_MS = 30_000L;

    /** ID for the "adapter is ready" notification posted when a scheduled probe connects. */
    private static final int ADAPTER_READY_NOTIFICATION_ID = 4711;

    private final MainActivity activity;

    private long adapterReadyDeadlineMs = 0L;
    private volatile boolean adapterReadyActive = false;

    /**
     * Distinguishes a probe-driven "connected" status from a user-initiated session that just
     * happens to be running while the C10 schedule is active. Set in {@link #startTestConnection},
     * cleared by the auto-stop callback or {@link #cancelAdapterReadyNotify}.
     */
    private volatile boolean probeInFlight = false;

    private Handler adapterReadyHandler;
    private Handler testConnectionStopHandler;

    private final Runnable adapterReadyTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!adapterReadyActive
                            || System.currentTimeMillis() >= adapterReadyDeadlineMs) {
                        adapterReadyActive = false;
                        return;
                    }
                    startTestConnection();
                    if (adapterReadyHandler != null && adapterReadyActive) {
                        adapterReadyHandler.postDelayed(this, ADAPTER_READY_INTERVAL_MS);
                    }
                }
            };

    TroubleshooterBridge(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * Drain both internal handlers. Called from {@link MainActivity#onDestroy}. Without this,
     * posted callbacks can fire on a destroyed Activity context and queue more callbacks
     * indefinitely.
     */
    void shutdown() {
        if (adapterReadyHandler != null) {
            adapterReadyHandler.removeCallbacksAndMessages(null);
        }
        if (testConnectionStopHandler != null) {
            testConnectionStopHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Called from {@link MainActivity#startObdService} so any pending test-connection auto-stop is
     * dropped before a fresh connect kicks off — otherwise the auto-stop would tear down the new
     * session 8 s in.
     */
    void clearPendingTestConnectionStop() {
        if (testConnectionStopHandler != null) {
            testConnectionStopHandler.removeCallbacksAndMessages(null);
        }
    }

    // ===== Bucket 4a bridge helpers ==========================================
    // Each helper is invoked from VoltBridge's BUCKET 4a region. Kept on the
    // Activity-bound bridge (and not on ObdService) so they have an Activity
    // context for startActivity / system service lookups without binding to
    // the service.

    /**
     * Bucket 4a (C4) — force-stop a competing app surfaced via the {@code competingApps} status
     * field. Uses {@link ActivityManager#killBackgroundProcesses(String)} — Android only acts on
     * background-promotable apps but it is the right API to expose. Returns true if the package is
     * installed (so the UI can surface the request honestly).
     */
    boolean forceStopPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            // Confirm the package exists on this device before issuing the kill — surfaces
            // "false" honestly when the user has uninstalled the competing app since the
            // status payload was generated.
            activity.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return false;
        }
        try {
            ActivityManager am =
                    (ActivityManager) activity.getSystemService(MainActivity.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            am.killBackgroundProcesses(packageName);
            Log.i(MainActivity.TAG, "forceStopPackage: requested kill of " + packageName);
            return true;
        } catch (SecurityException ex) {
            Log.w(MainActivity.TAG, "forceStopPackage denied for " + packageName, ex);
            return false;
        }
    }

    /**
     * Bucket 4a (C6) — forward a cancel request from the dashboard to the running ObdService so its
     * polling engine can bail out of the retry burst. No-op if the service is not bound / running.
     */
    void cancelRetry() {
        Intent service = new Intent(activity, ObdService.class);
        service.setAction(ObdService.ACTION_CANCEL_RETRY);
        try {
            activity.startService(service);
        } catch (IllegalStateException ignored) {
            // Service may have stopped between the JS click and the dispatch.
        }
    }

    /**
     * Bucket 4a (A8) — open Android Bluetooth settings so the user can forget the adapter and
     * re-pair. Falls back to a status broadcast if the activity is unavailable on this device.
     */
    void openBluetoothSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ex) {
            Log.w(MainActivity.TAG, "openBluetoothSettings failed", ex);
            activity.publishStatus(
                    "blocked",
                    "Could not open Bluetooth settings. Open Android Settings > Bluetooth"
                            + " manually.",
                    true);
        }
    }

    // ===== Bucket 4b bridge helpers ==========================================

    /**
     * Bucket 4b (C2, C8) — return the last {@code n} session summaries as a JSON array string,
     * newest first. Reads Bucket 3's {@link SessionSummaryStore}; returns {@code "[]"} on any
     * failure so the dashboard's last-connected badge / health pill code paths stay dormant instead
     * of crashing.
     */
    String getRecentSessionsJson(int n) {
        try {
            if (n <= 0) {
                return "[]";
            }
            SessionSummaryStore store = SessionSummaryStore.getInstance(activity.getFilesDir());
            List<SessionSummary> recent = store.getRecent(n);
            JSONArray arr = new JSONArray();
            for (SessionSummary s : recent) {
                arr.put(s.toJson());
            }
            return arr.toString();
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "getRecentSessionsJson failed", ex);
            return "[]";
        }
    }

    /**
     * Bucket 4b (C7) — bundle recent diagnostics into a zip via Bucket 3's helper and launch the
     * system share sheet. Errors surface as a status broadcast rather than throwing.
     */
    void shareDiagnostics() {
        try {
            Intent share = DiagnosticsShareIntent.buildIntent(activity);
            if (share == null) {
                activity.publishStatus(
                        "blocked",
                        "No diagnostics to share yet — connect once and try again.",
                        true);
                return;
            }
            activity.startActivity(Intent.createChooser(share, "Share diagnostics"));
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "shareDiagnostics failed", ex);
            activity.publishStatus("blocked", "Could not build the diagnostics share.", true);
        }
    }

    /**
     * Bucket 4b (C5) — start a one-shot probe session against the last-known adapter and schedule a
     * stop after {@link #TEST_CONNECTION_DURATION_MS} so we exercise the connect/init path without
     * committing to a full logging session.
     */
    void startTestConnection() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        final String address = device.optString("address", "");
        final String name = device.optString("name", "Test connection");
        if (address.isEmpty()) {
            activity.publishStatus(
                    "blocked",
                    "No remembered adapter yet — pick one and Connect once first.",
                    true);
            return;
        }
        activity.rememberDevice(address, name);
        // Gate for onAdapterStatusForReadyNotify: the very next "connected" broadcast (within
        // a reasonable window) belongs to this probe, so the schedule should fire its
        // notification. Cleared by the auto-stop callback below or by an explicit cancel.
        probeInFlight = true;
        activity.startObdService(ObdService.ACTION_CONNECT, address, name);
        // Defer the stop onto the main looper so the connect kicks off first. Use a single
        // long-lived Handler and clear any prior pending stop — without this, the C10 periodic
        // tick (every 30 s) would accumulate one independent stop Runnable per probe with no
        // way to cancel them, so a manual full-session connect started between probes could be
        // torn down 8 s after the next tick.
        if (testConnectionStopHandler == null) {
            testConnectionStopHandler = new Handler(activity.getMainLooper());
        }
        testConnectionStopHandler.removeCallbacksAndMessages(null);
        testConnectionStopHandler.postDelayed(
                () -> {
                    probeInFlight = false;
                    activity.stopObdService();
                },
                TEST_CONNECTION_DURATION_MS);
    }

    /**
     * Bucket 4b (C10) — periodic test-connection probe for {@code mins} minutes; the first status
     * broadcast with {@code state == "connected"} during the window posts a notification and
     * cancels the rest. {@code mins} is already clamped to [1, 30] by {@link
     * VoltBridge#scheduleAdapterReadyNotify(int)}.
     */
    void scheduleAdapterReadyNotify(int mins) {
        adapterReadyDeadlineMs = System.currentTimeMillis() + mins * 60_000L;
        adapterReadyActive = true;
        if (adapterReadyHandler == null) {
            adapterReadyHandler = new Handler(activity.getMainLooper());
        }
        // Reset any prior schedule and start fresh.
        adapterReadyHandler.removeCallbacksAndMessages(null);
        adapterReadyHandler.post(adapterReadyTick);
    }

    /**
     * Bucket 4b (C10) — cancel a running notify-when-ready schedule. Called from the bridge when
     * the user unchecks the toggle, and internally when the first successful probe lands or the
     * deadline expires.
     */
    void cancelAdapterReadyNotify() {
        adapterReadyActive = false;
        adapterReadyDeadlineMs = 0L;
        // Clear the probe-in-flight gate so a stale "connected" broadcast after a re-enable
        // doesn't mis-fire the notification on a non-probe session.
        probeInFlight = false;
        if (adapterReadyHandler != null) {
            adapterReadyHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Bucket 4b (C10) — called from the Activity's status-broadcast receiver. Fires the user-facing
     * "adapter is ready" notification when a probe started by the notify-when-ready schedule
     * reports {@code state == "connected"}, then tears down the schedule. Gated on {@link
     * #probeInFlight} so a "connected" broadcast for an unrelated session (e.g. the user manually
     * started one while the schedule happened to be active) does not fire the notification.
     */
    void onAdapterStatusForReadyNotify(String state) {
        if (!adapterReadyActive || !probeInFlight) {
            return;
        }
        if (!"connected".equals(state)) {
            return;
        }
        // On API 33+ this notification needs runtime POST_NOTIFICATIONS permission. We don't
        // demand it eagerly — if the user denied it, the schedule still works (it just won't
        // notify), and the next time they enter the app the UI shows the connected state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                activity, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.i(
                    MainActivity.TAG,
                    "adapter-ready: POST_NOTIFICATIONS not granted, skipping notification");
            cancelAdapterReadyNotify();
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager)
                            activity.getSystemService(MainActivity.NOTIFICATION_SERVICE);
            if (nm != null) {
                // Tap → open MainActivity. FLAG_IMMUTABLE is required on API 31+; we set it
                // unconditionally to keep the PendingIntent simple across versions.
                Intent open = new Intent(activity, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent tap =
                        PendingIntent.getActivity(
                                activity,
                                0,
                                open,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                // The (Context, channelId) Builder constructor is API 26+; the channel-less
                // form is the only legal call on minSdk=23 devices. Mirror the same gate as
                // ObdNotifications.build(...) so this notification path doesn't crash on
                // KitKat-era Androids that we still support.
                Notification.Builder b =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new Notification.Builder(activity, ObdNotifications.CHANNEL_ID)
                                : new Notification.Builder(activity);
                Notification n =
                        b.setContentTitle("OBD adapter is responding")
                                .setContentText("Tap to open VoltTracker and start logging.")
                                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                                .setContentIntent(tap)
                                .setAutoCancel(true)
                                .build();
                nm.notify(ADAPTER_READY_NOTIFICATION_ID, n);
            }
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "adapter-ready notification failed", ex);
        }
        cancelAdapterReadyNotify();
    }
}
