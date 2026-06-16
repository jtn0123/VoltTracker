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

    fun detailProbe(
        address: String?,
        name: String?,
        stage: String?,
    ) {
        val cleanAddress = VoltBridge.safe(address, VoltBridge.MAX_ADDRESS_LEN)
        val cleanName = VoltBridge.safe(name, VoltBridge.MAX_NAME_LEN)
        val cleanStage = EnhancedPidProfiles.normalizeStage(VoltBridge.safe(stage, VoltBridge.MAX_STAGE_LEN))
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!VoltBridge.validBluetoothAddress(cleanAddress)) {
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
        val cleanAddress = VoltBridge.safe(address, VoltBridge.MAX_ADDRESS_LEN)
        val cleanName = VoltBridge.safe(name, VoltBridge.MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
        }
    }

    fun connectLast() {
        startLastDeviceAction(
            ObdService.ACTION_CONNECT,
            "No remembered adapter yet. Connect once to save it.",
            requireValidAddress = true,
        )
    }

    fun scanLast() {
        startLastDeviceAction(
            ObdService.ACTION_SCAN,
            "No remembered adapter yet. Connect once to save it.",
            requireValidAddress = true,
        )
    }

    fun detailProbeLast(stage: String?) {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = VoltBridge.safe(device.optString("address", ""), VoltBridge.MAX_ADDRESS_LEN)
        val name = VoltBridge.safe(device.optString("name", ""), VoltBridge.MAX_NAME_LEN)
        val cleanStage = EnhancedPidProfiles.normalizeStage(VoltBridge.safe(stage, VoltBridge.MAX_STAGE_LEN))
        activity.runOnUiThread {
            if (!VoltBridge.validBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_TPMS_SCAN, address, name, cleanStage)
        }
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
        val address = VoltBridge.safe(device.optString("address", ""), VoltBridge.MAX_ADDRESS_LEN)
        val name = VoltBridge.safe(device.optString("name", ""), VoltBridge.MAX_NAME_LEN)
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
    ) {
        val cleanAddress = VoltBridge.safe(address, VoltBridge.MAX_ADDRESS_LEN)
        val cleanName = VoltBridge.safe(name, VoltBridge.MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!VoltBridge.validBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", invalidMessage, true)
                return@runOnUiThread
            }
            activity.startObdService(action, cleanAddress, cleanName)
        }
    }

    private fun startLastDeviceAction(
        action: String,
        invalidMessage: String,
        requireValidAddress: Boolean,
    ) {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = VoltBridge.safe(device.optString("address", ""), VoltBridge.MAX_ADDRESS_LEN)
        val name = VoltBridge.safe(device.optString("name", ""), VoltBridge.MAX_NAME_LEN)
        activity.runOnUiThread {
            val invalid = if (requireValidAddress) !VoltBridge.validBluetoothAddress(address) else address.isEmpty()
            if (invalid) {
                activity.publishStatus("blocked", invalidMessage, true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(action, address, name)
        }
    }

    private companion object {
        const val MSG_PICK_VALID_ADAPTER = "Choose a valid Bluetooth adapter."
    }
}
