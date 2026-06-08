package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONException
import org.json.JSONObject

/**
 * Captures a once-per-session "what does the world look like right now" snapshot: Android version,
 * app build, Bluetooth stack state, process uptime, and the timestamp of the most recent successful
 * session from [SessionSummaryStore].
 *
 * The recorder logs the snapshot as a `system_snapshot` event at session start so a future
 * diagnosis sees the device's actual state (right BT name? Android 13 vs 15? last successful
 * connect five seconds ago or five days?) without having to correlate three other log files.
 */
object SystemSnapshot {
    /**
     * Builds the snapshot. Pass a [SessionSummaryStore] so `lastSuccessfulSessionMs` can be filled
     * in; pass `null` to omit that field (e.g. during very early startup before the store is wired
     * up).
     */
    @JvmStatic
    fun collect(
        ctx: Context?,
        summaryStore: SessionSummaryStore?,
    ): JSONObject {
        val output = JSONObject()
        try {
            // -- Android version --
            output.put("androidSdk", Build.VERSION.SDK_INT)
            output.put("androidRelease", str(Build.VERSION.RELEASE))
            output.put("deviceManufacturer", str(Build.MANUFACTURER))
            output.put("deviceModel", str(Build.MODEL))

            // -- App version --
            if (ctx != null) {
                try {
                    val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    output.put("appVersionName", str(packageInfo.versionName))
                    output.put("appVersionCode", PackageInfoCompat.getLongVersionCode(packageInfo))
                } catch (ignored: PackageManager.NameNotFoundException) {
                    // Our own package not found is impossible in practice but we still don't want
                    // to escalate a missing-info case to a crash.
                }
            }

            // -- Bluetooth stack info --
            addBluetoothInfo(ctx, output)

            // -- Process uptime --
            output.put("processUptimeMs", SystemClock.elapsedRealtime())

            // -- Last successful session timestamp --
            if (summaryStore != null) {
                var lastOkMs = 0L
                for (summary in summaryStore.getRecent(20)) {
                    if (SessionSummary.OUTCOME_SUCCESS == summary.outcome && summary.endMs > lastOkMs) {
                        lastOkMs = summary.endMs
                    }
                }
                output.put("lastSuccessfulSessionMs", lastOkMs)
            }
        } catch (ignored: JSONException) {
            // Local strings/ints are safe.
        }
        return output
    }

    @Throws(JSONException::class)
    private fun addBluetoothInfo(
        ctx: Context?,
        output: JSONObject,
    ) {
        val adapter: BluetoothAdapter? =
            try {
                if (ctx == null) null else BluetoothAdapters.get(ctx)
            } catch (ex: RuntimeException) {
                // Some unit-test harnesses don't have a BT shadow and throw here. Treat as
                // "no adapter" and move on.
                output.put("btAdapterAvailable", false)
                output.put("btError", ex.javaClass.simpleName)
                return
            }
        if (adapter == null) {
            output.put("btAdapterAvailable", false)
            return
        }
        output.put("btAdapterAvailable", true)
        // Reading getName()/getAddress() needs BLUETOOTH_CONNECT on Android 12+. If we don't
        // have it (yet), record that fact instead of throwing -- it's still a useful signal in
        // the snapshot ("user hadn't granted CONNECT when the session opened").
        val canRead = canReadBtIdentity(ctx)
        output.put("btIdentityReadable", canRead)
        // We deliberately do not capture BluetoothAdapter.getAddress(): Android 8+ returns the
        // placeholder 02:00:00:00:00:00 unless the caller holds LOCAL_MAC_ADDRESS (a privileged
        // permission), which we don't, so the value would be useless and would also trip
        // HardwareIds lint warnings on every build. The local adapter's name is enough signal for
        // a diagnostic snapshot.
        if (canRead) {
            try {
                val name = adapter.name
                if (name != null) {
                    output.put("btName", name)
                }
            } catch (ignored: SecurityException) {
                // Permission was revoked between our check and the call.
                output.put("btIdentityReadable", false)
            }
        }
        try {
            output.put("btEnabled", adapter.isEnabled)
        } catch (ignored: SecurityException) {
            // isEnabled() is permission-free on most platforms, but be defensive.
        }
    }

    private fun canReadBtIdentity(ctx: Context?): Boolean {
        if (ctx == null) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun str(s: String?): String = s ?: ""
}
