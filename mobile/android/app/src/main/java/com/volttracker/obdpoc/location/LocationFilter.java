package com.volttracker.obdpoc.location;

import java.util.Locale;

/**
 * Pure decision logic for accepting or rejecting a raw location fix before it becomes a
 * stored route point. Has no Android dependencies so it is unit-testable on the JVM.
 *
 * <p>Rejection rules, applied in order:
 * <ul>
 *   <li>accuracy worse than {@code maxAccuracyM}</li>
 *   <li>a fix whose own timestamp is older than {@code staleFixMs} (a cached/replayed fix)</li>
 *   <li>a coarse {@code network} fix that arrives while GPS fixes are still fresh</li>
 *   <li>a physically impossible jump from the last accepted point</li>
 * </ul>
 *
 * <p>The jump check is bypassed once {@code maxConsecutiveRejects} fixes in a row have been
 * rejected, so a stale "last accepted" point cannot wedge the filter shut forever.
 */
public final class LocationFilter {

    public enum Decision {
        ACCEPT,
        REJECT_ACCURACY,
        REJECT_STALE,
        REJECT_PROVIDER,
        REJECT_JUMP
    }

    static final float DEFAULT_MAX_ACCURACY_M = 50f;
    static final long DEFAULT_STALE_FIX_MS = 10_000L;
    static final long DEFAULT_NETWORK_SUPPRESS_MS = 15_000L;
    static final double DEFAULT_MAX_IMPLIED_SPEED_MPS = 67.0; // ~150 mph; clearly a teleport above this
    static final int DEFAULT_MAX_CONSECUTIVE_REJECTS = 5;

    private final float maxAccuracyM;
    private final long staleFixMs;
    private final long networkSuppressMs;
    private final double maxImpliedSpeedMps;
    private final int maxConsecutiveRejects;

    private boolean hasLast;
    private double lastLat;
    private double lastLng;
    private long lastFixMs;
    private long lastGpsAcceptMs;
    private int consecutiveRejects;

    public LocationFilter() {
        this(DEFAULT_MAX_ACCURACY_M, DEFAULT_STALE_FIX_MS, DEFAULT_NETWORK_SUPPRESS_MS,
                DEFAULT_MAX_IMPLIED_SPEED_MPS, DEFAULT_MAX_CONSECUTIVE_REJECTS);
    }

    public LocationFilter(float maxAccuracyM, long staleFixMs, long networkSuppressMs,
                          double maxImpliedSpeedMps, int maxConsecutiveRejects) {
        this.maxAccuracyM = maxAccuracyM;
        this.staleFixMs = staleFixMs;
        this.networkSuppressMs = networkSuppressMs;
        this.maxImpliedSpeedMps = maxImpliedSpeedMps;
        this.maxConsecutiveRejects = maxConsecutiveRejects;
    }

    /**
     * @param accuracyM horizontal accuracy in metres, or a non-positive value when unknown
     * @param fixTimeMs the fix's own timestamp (epoch ms), or non-positive when unknown
     * @param provider  the originating provider name (e.g. "gps", "network", "fused")
     * @param nowMs     current wall-clock time
     */
    public Decision evaluate(double lat, double lng, float accuracyM,
                             long fixTimeMs, String provider, long nowMs) {
        if (accuracyM > 0f && accuracyM > maxAccuracyM) {
            return reject(Decision.REJECT_ACCURACY);
        }
        if (fixTimeMs > 0L && nowMs - fixTimeMs > staleFixMs) {
            return reject(Decision.REJECT_STALE);
        }
        boolean network = isNetworkProvider(provider);
        if (network && lastGpsAcceptMs > 0L && nowMs - lastGpsAcceptMs <= networkSuppressMs) {
            return reject(Decision.REJECT_PROVIDER);
        }
        boolean resync = consecutiveRejects >= maxConsecutiveRejects;
        if (hasLast && !resync) {
            long effectiveFix = fixTimeMs > 0L ? fixTimeMs : nowMs;
            double dtSeconds = (effectiveFix - lastFixMs) / 1000.0;
            if (dtSeconds > 0) {
                double mps = haversineMeters(lastLat, lastLng, lat, lng) / dtSeconds;
                if (mps > maxImpliedSpeedMps) {
                    return reject(Decision.REJECT_JUMP);
                }
            }
        }
        accept(lat, lng, fixTimeMs > 0L ? fixTimeMs : nowMs, network, nowMs);
        return Decision.ACCEPT;
    }

    private void accept(double lat, double lng, long fixMs, boolean network, long nowMs) {
        hasLast = true;
        lastLat = lat;
        lastLng = lng;
        lastFixMs = fixMs;
        if (!network) {
            lastGpsAcceptMs = nowMs;
        }
        consecutiveRejects = 0;
    }

    private Decision reject(Decision why) {
        consecutiveRejects += 1;
        return why;
    }

    /** Number of fixes rejected since the last accepted fix; exposed for diagnostics/tests. */
    public int consecutiveRejects() {
        return consecutiveRejects;
    }

    /** Clears all per-session state so the filter can be reused for a fresh tracking session. */
    public void reset() {
        hasLast = false;
        lastLat = 0;
        lastLng = 0;
        lastFixMs = 0L;
        lastGpsAcceptMs = 0L;
        consecutiveRejects = 0;
    }

    static boolean isNetworkProvider(String provider) {
        return provider != null && provider.toLowerCase(Locale.US).contains("network");
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
