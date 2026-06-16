package com.volttracker.obdpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.Arrays

/**
 * Triggers an SDP UUID refresh on the OBD adapter device.
 */
open class SdpProbe {
    private val service: ObdService?
    private val cooldownMs: Long
    private var lastTriggeredAtMs = 0L

    constructor(service: ObdService?) : this(service, DEFAULT_COOLDOWN_MS)

    constructor(service: ObdService?, cooldownMs: Long) {
        this.service = service
        this.cooldownMs = cooldownMs
    }

    @SuppressLint("MissingPermission")
    open fun trigger(address: String?): Boolean {
        val currentService = service
        if (currentService == null || address == null || address.trim().isEmpty()) {
            return false
        }
        if (!currentService.hasBluetoothConnectPermission()) {
            log("sdp_refresh_skipped", address, "reason", "missing_bluetooth_connect")
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastTriggeredAtMs < cooldownMs) {
            log(
                "sdp_refresh_skipped",
                address,
                "reason",
                "cooldown",
                "sinceLastMs",
                (now - lastTriggeredAtMs).toString(),
            )
            return false
        }
        val adapter = BluetoothAdapters.get(currentService)
        if (adapter == null) {
            log("sdp_refresh_skipped", address, "reason", "no_adapter")
            return false
        }
        val device =
            try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                log("sdp_refresh_skipped", address, "reason", "invalid_address")
                return false
            }
        val dispatched =
            try {
                device.fetchUuidsWithSdp()
            } catch (ex: SecurityException) {
                log("sdp_refresh_skipped", address, "reason", "security_exception")
                return false
            }
        lastTriggeredAtMs = now
        log(
            "sdp_refresh_requested",
            address,
            "dispatched",
            dispatched.toString(),
            "cachedUuids",
            safeUuids(device),
        )
        return dispatched
    }

    @SuppressLint("MissingPermission")
    private fun safeUuids(device: BluetoothDevice): String {
        val currentService = service
        if (currentService == null || !currentService.hasBluetoothConnectPermission()) {
            return ""
        }
        return try {
            val uuids = device.uuids
            if (uuids == null) "[]" else Arrays.toString(uuids)
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun log(
        event: String,
        address: String,
        vararg extras: String,
    ) {
        val currentService = service ?: return
        val recorder = currentService.recorder ?: return
        val pairs = arrayOfNulls<String>(2 + extras.size)
        pairs[0] = "address"
        pairs[1] = address
        System.arraycopy(extras, 0, pairs, 2, extras.size)
        @Suppress("UNCHECKED_CAST")
        recorder.logEvent(event, *(pairs as Array<String>))
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 10_000L
    }
}
