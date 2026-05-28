package com.volttracker.obdpoc.data;

public final class RecentSessionSummaryRecord {
    public final ObdSessionRecord session;
    public final long usefulSampleCount;
    public final long emptySampleCount;

    RecentSessionSummaryRecord(
            ObdSessionRecord session, long usefulSampleCount, long emptySampleCount) {
        this.session = session;
        this.usefulSampleCount = usefulSampleCount;
        this.emptySampleCount = emptySampleCount;
    }
}
