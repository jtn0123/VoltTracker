package com.volttracker.obdpoc.data

class AdapterHistoryRecord(
    adapterKey: String?,
    address: String?,
    name: String?,
    @JvmField val firstSeenMs: Long,
    @JvmField val lastSeenMs: Long,
    @JvmField val connectCount: Int,
    @JvmField val scanCount: Int,
    @JvmField val demoCount: Int,
    @JvmField val sampleCount: Int,
    @JvmField val lastSessionId: Long,
    lastMode: String?,
    lastStatus: String?,
    supportedPids: String?,
    lastEventDetail: String?,
) {
    @JvmField val adapterKey: String = adapterKey ?: ""

    @JvmField val address: String = address ?: ""

    @JvmField val name: String = name ?: ""

    @JvmField val lastMode: String = lastMode ?: ""

    @JvmField val lastStatus: String = lastStatus ?: ""

    @JvmField val supportedPids: String = supportedPids ?: ""

    @JvmField val lastEventDetail: String = lastEventDetail ?: ""
}
