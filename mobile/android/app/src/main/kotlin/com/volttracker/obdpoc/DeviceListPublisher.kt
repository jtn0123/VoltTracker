package com.volttracker.obdpoc

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads paired-device/history JSON off the UI thread, then publishes both dashboard payloads
 * together. Startup and permission refreshes can ask for this repeatedly; in-flight reads are
 * coalesced so Bluetooth/SharedPreferences are not re-read concurrently.
 */
internal class DeviceListPublisher(
    private val submitBackground: (Runnable) -> Unit,
    private val runOnUi: (Runnable) -> Unit,
    private val readDeviceJson: () -> DeviceListPayload,
    private val publishDeviceJson: (DeviceListPayload) -> Unit,
) {
    private val inFlight = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)

    fun publish() {
        if (!inFlight.compareAndSet(false, true)) {
            queued.set(true)
            return
        }
        runRefresh()
    }

    private fun runRefresh() {
        submitBackground(
            Runnable {
                try {
                    StartupTrace.mark("publish_device_list_read_start")
                    val payload = readDeviceJson()
                    StartupTrace.mark("publish_device_list_read_end")
                    runOnUi(
                        Runnable {
                            StartupTrace.mark("publish_device_list_publish_start")
                            publishDeviceJson(payload)
                            StartupTrace.mark("publish_device_list_publish_end")
                            StartupTrace.mark("publish_device_list_end")
                        },
                    )
                } finally {
                    inFlight.set(false)
                    if (queued.getAndSet(false)) {
                        publish()
                    }
                }
            },
        )
    }
}

internal data class DeviceListPayload(
    val devicesJson: String,
    val historyJson: String,
)
