package com.volttracker.obdpoc.data;

public final class ObdSessionRecord {
    public final long id;
    public final String mode;
    public final String adapterAddress;
    public final String adapterName;
    public final long startedAtMs;
    public final long endedAtMs;
    public final String status;
    public final String supportedPids;
    public final int sampleCount;
    public final long lastEventAtMs;

    ObdSessionRecord(
            long id,
            String mode,
            String adapterAddress,
            String adapterName,
            long startedAtMs,
            long endedAtMs,
            String status,
            String supportedPids,
            int sampleCount,
            long lastEventAtMs) {
        this.id = id;
        this.mode = nonNull(mode);
        this.adapterAddress = nonNull(adapterAddress);
        this.adapterName = nonNull(adapterName);
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.status = nonNull(status);
        this.supportedPids = nonNull(supportedPids);
        this.sampleCount = sampleCount;
        this.lastEventAtMs = lastEventAtMs;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
