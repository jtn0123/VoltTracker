package com.volttracker.obdpoc.data;

public final class AdapterHistoryRecord {
    public final String adapterKey;
    public final String address;
    public final String name;
    public final long firstSeenMs;
    public final long lastSeenMs;
    public final int connectCount;
    public final int scanCount;
    public final int demoCount;
    public final int sampleCount;
    public final long lastSessionId;
    public final String lastMode;
    public final String lastStatus;
    public final String supportedPids;
    public final String lastEventDetail;

    AdapterHistoryRecord(
            String adapterKey,
            String address,
            String name,
            long firstSeenMs,
            long lastSeenMs,
            int connectCount,
            int scanCount,
            int demoCount,
            int sampleCount,
            long lastSessionId,
            String lastMode,
            String lastStatus,
            String supportedPids,
            String lastEventDetail
    ) {
        this.adapterKey = nonNull(adapterKey);
        this.address = nonNull(address);
        this.name = nonNull(name);
        this.firstSeenMs = firstSeenMs;
        this.lastSeenMs = lastSeenMs;
        this.connectCount = connectCount;
        this.scanCount = scanCount;
        this.demoCount = demoCount;
        this.sampleCount = sampleCount;
        this.lastSessionId = lastSessionId;
        this.lastMode = nonNull(lastMode);
        this.lastStatus = nonNull(lastStatus);
        this.supportedPids = nonNull(supportedPids);
        this.lastEventDetail = nonNull(lastEventDetail);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
