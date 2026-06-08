package com.volttracker.obdpoc.classify

/**
 * Coarse "what is the vehicle doing right now?" enum that the dashboard renders. The single source
 * of truth lives in `VehicleStateClassifier`; everywhere else (parsers, JS) reads the result via
 * [asPayloadKey] rather than re-deriving the answer.
 */
enum class VehicleState {
    UNKNOWN,
    PARKED,
    DRIVING_EV,
    DRIVING_GAS,
    READY,
    PLUGGED,
    CHARGING,
    ;

    /**
     * Stable string used on the app-state JSON payload and the JS side. The `when` is exhaustive
     * with no `else`: adding a new enum constant must update this method, and the compiler will
     * flag the miss.
     */
    fun asPayloadKey(): String =
        when (this) {
            UNKNOWN -> "unknown"
            PARKED -> "parked"
            DRIVING_EV -> "driving_ev"
            DRIVING_GAS -> "driving_gas"
            READY -> "ready"
            PLUGGED -> "plugged"
            CHARGING -> "charging"
        }
}
