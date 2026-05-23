package com.volttracker.obdpoc.classify;

/**
 * Coarse "what is the vehicle doing right now?" enum that the dashboard renders. The single source
 * of truth lives in {@link VehicleStateClassifier}; everywhere else (parsers, JS) reads the result
 * via {@link #asPayloadKey()} rather than re-deriving the answer.
 */
public enum VehicleState {
    UNKNOWN,
    PARKED,
    DRIVING_EV,
    DRIVING_GAS,
    READY,
    PLUGGED,
    CHARGING;

    /**
     * Stable string used on the app-state JSON payload and the JS side. No {@code default} branch:
     * adding a new enum constant must update this method, and the compiler will flag the miss.
     */
    public String asPayloadKey() {
        switch (this) {
            case UNKNOWN:
                return "unknown";
            case PARKED:
                return "parked";
            case DRIVING_EV:
                return "driving_ev";
            case DRIVING_GAS:
                return "driving_gas";
            case READY:
                return "ready";
            case PLUGGED:
                return "plugged";
            case CHARGING:
                return "charging";
        }
        throw new AssertionError("Unhandled VehicleState: " + this);
    }
}
