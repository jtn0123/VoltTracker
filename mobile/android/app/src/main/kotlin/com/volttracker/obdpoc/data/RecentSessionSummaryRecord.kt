package com.volttracker.obdpoc.data

class RecentSessionSummaryRecord(
    @JvmField val session: ObdSessionRecord,
    @JvmField val usefulSampleCount: Long,
    @JvmField val emptySampleCount: Long,
)
