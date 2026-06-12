package com.volttracker.obdpoc

import android.content.Context

/**
 * Owns the connect-attempt / runtime-permission handshake extracted from [MainActivity]:
 * parking a connect/scan request while Android shows the Bluetooth permission prompt, resuming it
 * automatically once the grant lands, and explaining denials with a recovery path.
 *
 * Collaborators are injected as narrow lambdas (same style as [StorageSummaryPublisher] and
 * [DashboardBroadcastCoordinator]) so the decision tree is unit-testable without a WebView.
 */
class ConnectPermissionFlow(
    private val context: Context,
    private val permissionGate: () -> PermissionGate,
    private val publishStatus: (state: String, detail: String, blocked: Boolean) -> Unit,
    private val publishDeviceList: () -> Unit,
    private val startObdService: (action: String?, address: String?, name: String?, detailStage: String?) -> Unit,
    private val canAskForBluetoothConnectAgain: () -> Boolean,
    private val openAppPermissionSettings: () -> Boolean,
) {
    /** Connect/scan request parked while Android shows the Bluetooth permission prompt. */
    private class PendingObdStart(
        val action: String?,
        val address: String?,
        val name: String?,
        val detailStage: String?,
    ) {
        fun label(context: Context): String =
            name?.takeIf { it.isNotBlank() }
                ?: address?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.obd_adapter_fallback_label)
    }

    private var pending: PendingObdStart? = null

    /**
     * Parks a connect/scan request whose only blocker is the Bluetooth permission prompt that was
     * just launched, so [onPermissionsResult] can finish it instead of dead-ending the tap.
     */
    fun parkPendingStart(
        action: String?,
        address: String?,
        name: String?,
        detailStage: String?,
    ) {
        pending = PendingObdStart(action, address, name, detailStage)
        publishStatus("blocked", context.getString(R.string.status_waiting_bluetooth_permission), true)
    }

    /**
     * Reacts to a runtime-permission result. The decision re-reads the live grant state through
     * [PermissionGate] (not the result map), so it stays correct regardless of how the request was
     * delivered.
     */
    fun onPermissionsResult() {
        publishDeviceList()
        val gate = permissionGate()
        val parked = pending
        pending = null
        // Gate on the same full permission set the start path requests (CONNECT + SCAN on 12+):
        // a partial grant must not resume a parked connect that would immediately fail. This is
        // the non-prompting check — calling ensureConnectPermissions() here would re-request and
        // loop the prompt.
        if (!gate.hasConnectPermissions()) {
            publishBluetoothPermissionDenied(wasConnecting = parked != null)
        } else if (parked != null) {
            // The user already asked for this connection and the permission was the only blocker,
            // so continue automatically instead of bouncing them back to the Connect button.
            publishStatus(
                "connecting",
                context.getString(R.string.status_bt_granted_connecting, parked.label(context)),
                false,
            )
            startObdService(parked.action, parked.address, parked.name, parked.detailStage)
        } else if (!gate.hasLocation()) {
            publishStatus("ready", context.getString(R.string.status_bt_granted_location_off), false)
        } else if (!gate.hasNotifications()) {
            publishStatus("ready", context.getString(R.string.status_bt_granted_notifications_off), false)
        } else {
            publishStatus("ready", context.getString(R.string.status_bt_granted_pick_adapter), false)
        }
    }

    /**
     * Explains a denied Bluetooth permission with a recovery path. Once Android suppresses the
     * prompt (repeated denials), the only fix left is the app-settings screen — open it when the
     * denial interrupted an active connect attempt so the user can finish in one round trip.
     */
    private fun publishBluetoothPermissionDenied(wasConnecting: Boolean) {
        if (canAskForBluetoothConnectAgain()) {
            publishStatus("blocked", context.getString(R.string.status_bt_denied_retry), true)
            return
        }
        val openedSettings = wasConnecting && openAppPermissionSettings()
        publishStatus(
            "blocked",
            context.getString(
                if (openedSettings) {
                    R.string.status_bt_denied_settings_opened
                } else {
                    R.string.status_bt_denied_open_settings
                },
            ),
            true,
        )
    }
}
