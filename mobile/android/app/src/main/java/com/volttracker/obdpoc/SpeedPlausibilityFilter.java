package com.volttracker.obdpoc;

/**
 * Rejects implausible OBD vehicle-speed readings: out-of-range values and physically
 * impossible jumps from the last accepted reading. Mirrors the GPS {@code LocationFilter}
 * pattern. Pure and stateful-but-deterministic, so it is unit-testable without a device.
 */
final class SpeedPlausibilityFilter {

    /** Above this change-per-second the reading is a glitch, not real acceleration. */
    static final double MAX_JUMP_KPH_PER_SEC = 45.0;

    private Integer lastAcceptedKph;
    private long lastAcceptedAtMs;

    /** Clears state so a new session starts fresh. */
    void reset() {
        lastAcceptedKph = null;
        lastAcceptedAtMs = 0L;
    }

    /**
     * @param speedKph the freshly decoded speed
     * @param nowMs    current time, supplied so the decision is testable
     * @return true if the reading is plausible and should be accepted
     */
    boolean accept(int speedKph, long nowMs) {
        if (speedKph < 0 || speedKph >= 255) {
            return false;
        }
        if (lastAcceptedKph == null || lastAcceptedAtMs <= 0L) {
            lastAcceptedKph = speedKph;
            lastAcceptedAtMs = nowMs;
            return true;
        }
        double elapsedSeconds = Math.max(0.5, (nowMs - lastAcceptedAtMs) / 1000.0);
        double jumpPerSecond = Math.abs(speedKph - lastAcceptedKph) / elapsedSeconds;
        if (jumpPerSecond > MAX_JUMP_KPH_PER_SEC) {
            return false;
        }
        lastAcceptedKph = speedKph;
        lastAcceptedAtMs = nowMs;
        return true;
    }
}
