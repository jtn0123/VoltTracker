package com.volttracker.obdpoc

internal class VoltBridgeConnections(
    private val activity: DashboardHost,
) {
    fun connect(
        address: String?,
        name: String?,
    ) {
        startSelectedDeviceAction(address, name, ObdService.ACTION_CONNECT, MSG_PICK_VALID_ADAPTER)
    }

    fun scan(
        address: String?,
        name: String?,
    ) {
        startSelectedDeviceAction(address, name, ObdService.ACTION_SCAN, MSG_PICK_VALID_ADAPTER)
    }

    fun scanProfile(
        address: String?,
        name: String?,
        profile: String?,
    ) {
        startSelectedDeviceAction(
            address,
            name,
            ObdService.ACTION_SCAN,
            MSG_PICK_VALID_ADAPTER,
            DiagnosticScanProfile.fromWireName(profile).wireName,
        )
    }

    fun detailProbe(
        address: String?,
        name: String?,
        stage: String?,
    ) {
        val cleanAddress = bridgeSafe(address, BRIDGE_MAX_ADDRESS_LEN)
        val cleanName = bridgeSafe(name, BRIDGE_MAX_NAME_LEN)
        val cleanStage = EnhancedPidProfiles.normalizeStage(bridgeSafe(stage, BRIDGE_MAX_STAGE_LEN))
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!validBridgeBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", MSG_PICK_VALID_ADAPTER, true)
                return@runOnUiThread
            }
            activity.startObdService(ObdService.ACTION_TPMS_SCAN, cleanAddress, cleanName, cleanStage)
        }
    }

    fun rememberDevice(
        address: String?,
        name: String?,
    ) {
        val cleanAddress = bridgeSafe(address, BRIDGE_MAX_ADDRESS_LEN)
        val cleanName = bridgeSafe(name, BRIDGE_MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
        }
    }

    fun connectLast() {
        startLastDeviceAction(
            ObdService.ACTION_CONNECT,
            MSG_NO_REMEMBERED_ADAPTER,
            requireValidAddress = true,
        )
    }

    fun scanLast() {
        startLastDeviceAction(
            ObdService.ACTION_SCAN,
            MSG_NO_REMEMBERED_ADAPTER,
            requireValidAddress = true,
        )
    }

    fun detailProbeLast(stage: String?) {
        val cleanStage = EnhancedPidProfiles.normalizeStage(bridgeSafe(stage, BRIDGE_MAX_STAGE_LEN))
        startLastDeviceAction(
            ObdService.ACTION_TPMS_SCAN,
            MSG_NO_REMEMBERED_ADAPTER,
            requireValidAddress = true,
            stage = cleanStage,
        )
    }

    fun demo() {
        activity.runOnUiThread {
            activity.startObdService(ObdService.ACTION_DEMO, null, "Demo stream")
        }
    }

    fun disconnect() {
        activity.runOnUiThread(activity::stopObdService)
    }

    fun cancelRetry() {
        activity.runOnUiThread(activity::cancelRetryFromBridge)
    }

    fun tryReconnectNow() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = bridgeSafe(device.optString("address", ""), BRIDGE_MAX_ADDRESS_LEN)
        val name = bridgeSafe(device.optString("name", ""), BRIDGE_MAX_NAME_LEN)
        activity.runOnUiThread {
            if (address.isEmpty()) {
                activity.publishStatus("blocked", "No remembered adapter yet. Pick one and try Connect.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_CONNECT, address, name)
        }
    }

    fun openBluetoothSettings() {
        activity.runOnUiThread(activity::openBluetoothSettingsFromBridge)
    }

    private fun startSelectedDeviceAction(
        address: String?,
        name: String?,
        action: String,
        invalidMessage: String,
        profile: String? = null,
    ) {
        val cleanAddress = bridgeSafe(address, BRIDGE_MAX_ADDRESS_LEN)
        val cleanName = bridgeSafe(name, BRIDGE_MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!validBridgeBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", invalidMessage, true)
                return@runOnUiThread
            }
            if (profile == null) {
                activity.startObdService(action, cleanAddress, cleanName)
            } else {
                activity.startObdService(action, cleanAddress, cleanName, profile)
            }
        }
    }

    private fun startLastDeviceAction(
        action: String,
        invalidMessage: String,
        requireValidAddress: Boolean,
        stage: String? = null,
    ) {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = bridgeSafe(device.optString("address", ""), BRIDGE_MAX_ADDRESS_LEN)
        val name = bridgeSafe(device.optString("name", ""), BRIDGE_MAX_NAME_LEN)
        activity.runOnUiThread {
            val invalid = if (requireValidAddress) !validBridgeBluetoothAddress(address) else address.isEmpty()
            if (invalid) {
                activity.publishStatus("blocked", invalidMessage, true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            if (stage != null) {
                activity.startObdService(action, address, name, stage)
            } else {
                activity.startObdService(action, address, name)
            }
        }
    }

    private companion object {
        const val MSG_PICK_VALID_ADAPTER = "Choose a valid Bluetooth adapter."
        const val MSG_NO_REMEMBERED_ADAPTER = "No remembered adapter yet. Connect once to save it."
    }
}
