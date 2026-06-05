package com.volttracker.obdpoc

private fun cleanProfileField(value: String?): String = value?.trim() ?: ""

/** Static metadata for one enhanced/candidate telemetry item. */
class EnhancedPidProfile(
    key: String?,
    category: String?,
    network: String?,
    protocol: String?,
    header: String?,
    command: String?,
    pid: String?,
    name: String?,
    unit: String?,
    pollLane: String?,
    scanStage: String?,
    risk: String?,
    retryAfterMs: Long,
    validationStatus: String?,
    source: String?,
    notes: String?,
) {
    @JvmField val key: String = cleanProfileField(key)

    @JvmField val category: String = cleanProfileField(category)

    @JvmField val network: String = cleanProfileField(network)

    @JvmField val protocol: String = cleanProfileField(protocol)

    @JvmField val header: String = cleanProfileField(header)

    @JvmField val command: String = cleanProfileField(command)

    @JvmField val pid: String = cleanProfileField(pid)

    @JvmField val name: String = cleanProfileField(name)

    @JvmField val unit: String = cleanProfileField(unit)

    @JvmField val pollLane: String = cleanProfileField(pollLane)

    @JvmField val scanStage: String = cleanProfileField(scanStage)

    @JvmField val risk: String = cleanProfileField(risk)

    @JvmField val retryAfterMs: Long = maxOf(0L, retryAfterMs)

    @JvmField val validationStatus: String = cleanProfileField(validationStatus)

    @JvmField val source: String = cleanProfileField(source)

    @JvmField val notes: String = cleanProfileField(notes)
}
