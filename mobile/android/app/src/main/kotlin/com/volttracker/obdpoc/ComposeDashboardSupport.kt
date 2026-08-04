package com.volttracker.obdpoc

import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.update.UpdateManager

/** What tapping Connect must do, given the remembered adapter and radio state. */
internal enum class ConnectAction {
    /** No adapter has ever been remembered — pairing lives in the classic dashboard. */
    OPEN_CLASSIC,
    REQUEST_PERMISSION,
    REQUEST_ENABLE_BLUETOOTH,
    CONNECT,
}

/**
 * The decision logic split out of [ComposeDashboardActivity], mirroring the
 * [MainActivityUtils] pattern: the activity stays framework glue while the
 * branches live here where plain JVM tests can reach them.
 */
internal object ComposeDashboardSupport {
    fun decideConnectAction(
        lastAddress: String?,
        hasConnectPermission: Boolean,
        bluetoothEnabled: Boolean,
    ): ConnectAction =
        when {
            lastAddress.isNullOrBlank() -> ConnectAction.OPEN_CLASSIC
            !hasConnectPermission -> ConnectAction.REQUEST_PERMISSION
            !bluetoothEnabled -> ConnectAction.REQUEST_ENABLE_BLUETOOTH
            else -> ConnectAction.CONNECT
        }

    /**
     * Routes one service broadcast into the live store. Returns false when the
     * payload is empty/garbage or the action is not a dashboard feed.
     */
    fun routeServiceBroadcast(
        action: String?,
        json: String?,
        store: LiveUiStateStore,
    ): Boolean {
        val payload = MainActivityUtils.parseJson(json)
        if (payload.length() == 0) return false
        return when (action) {
            ObdService.BROADCAST_TELEMETRY -> {
                store.onTelemetry(payload)
                true
            }
            ObdService.BROADCAST_STATUS -> {
                store.onStatus(payload)
                true
            }
            else -> false
        }
    }

    /** The Settings update section's rendering of one check outcome. */
    data class UpdateBanner(
        val statusLabel: String,
        /** Non-null exactly when a newer build can be installed. */
        val availableTag: String?,
    )

    /** Maps an update-check result to what the Settings section should say. */
    fun updateBanner(result: UpdateManager.CheckResult): UpdateBanner =
        when (result) {
            is UpdateManager.CheckResult.UpdateAvailable ->
                UpdateBanner("${result.build.tag} is available", result.build.tag)
            UpdateManager.CheckResult.UpToDate -> UpdateBanner("Up to date", null)
            UpdateManager.CheckResult.NoBuilds -> UpdateBanner("No published builds yet", null)
            is UpdateManager.CheckResult.Unknown ->
                UpdateBanner("Newest is ${result.build.tag} — can't compare to this build", null)
            UpdateManager.CheckResult.Offline ->
                UpdateBanner("Couldn't reach GitHub — check connection", null)
            is UpdateManager.CheckResult.Failed -> UpdateBanner(result.detail, null)
        }

    /**
     * Replays the service's in-process snapshot into the store — the same
     * resume catch-up the WebView dashboard performs, minus the toast.
     */
    fun replayServiceSnapshot(store: LiveUiStateStore) {
        val status = LiveDashboardSnapshot.latestStatus()
        if (status.length() > 0) store.onStatus(status)
        val history = LiveDashboardSnapshot.telemetryHistorySince(0L)
        if (history.isNotEmpty()) store.onTelemetryBackfill(history)
    }
}
