package com.volttracker.obdpoc.data

class ObdSessionRecord(
    @JvmField val id: Long,
    mode: String?,
    adapterAddress: String?,
    adapterName: String?,
    @JvmField val startedAtMs: Long,
    @JvmField val endedAtMs: Long,
    status: String?,
    supportedPids: String?,
    @JvmField val sampleCount: Int,
    @JvmField val lastEventAtMs: Long,
) {
    @JvmField val mode: String = mode ?: ""

    @JvmField val adapterAddress: String = adapterAddress ?: ""

    @JvmField val adapterName: String = adapterName ?: ""

    @JvmField val status: String = status ?: ""

    @JvmField val supportedPids: String = supportedPids ?: ""
}
