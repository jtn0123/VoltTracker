package com.volttracker.obdpoc;

/** Static metadata for one enhanced/candidate telemetry item. */
public final class EnhancedPidProfile {
    public final String key;
    public final String category;
    public final String network;
    public final String protocol;
    public final String header;
    public final String command;
    public final String pid;
    public final String name;
    public final String unit;
    public final String pollLane;
    public final String scanStage;
    public final String risk;
    public final long retryAfterMs;
    public final String validationStatus;
    public final String source;
    public final String notes;

    EnhancedPidProfile(
            String key,
            String category,
            String network,
            String protocol,
            String header,
            String command,
            String pid,
            String name,
            String unit,
            String pollLane,
            String scanStage,
            String risk,
            long retryAfterMs,
            String validationStatus,
            String source,
            String notes) {
        this.key = clean(key);
        this.category = clean(category);
        this.network = clean(network);
        this.protocol = clean(protocol);
        this.header = clean(header);
        this.command = clean(command);
        this.pid = clean(pid);
        this.name = clean(name);
        this.unit = clean(unit);
        this.pollLane = clean(pollLane);
        this.scanStage = clean(scanStage);
        this.risk = clean(risk);
        this.retryAfterMs = Math.max(0L, retryAfterMs);
        this.validationStatus = clean(validationStatus);
        this.source = clean(source);
        this.notes = clean(notes);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
