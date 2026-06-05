package com.volttracker.obdpoc.classify

/**
 * What `VehicleStateClassifier` returns: the state itself, how confident we are, and the short
 * reason codes that justify the decision (useful for telemetry and UI tooltips). The `reasons` list
 * is unmodifiable so callers can hand it to the JSON layer without copying. `state` and `confidence`
 * are non-null (Kotlin rejects nulls at construction) so downstream payload code can fail fast
 * rather than NPE deep in JSON serialisation.
 */
class ClassifierResult(
    @JvmField val state: VehicleState,
    @JvmField val confidence: Confidence,
    reasons: List<String>?,
) {
    @JvmField val reasons: List<String> =
        if (reasons == null) emptyList() else java.util.List.copyOf(reasons)
}
