package com.volttracker.obdpoc.classify;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What {@link VehicleStateClassifier} returns: the state itself, how confident we are, and the
 * short reason codes that justify the decision (useful for telemetry and UI tooltips). The {@code
 * reasons} list is unmodifiable so callers can hand it to the JSON layer without copying. {@code
 * state} and {@code confidence} are validated non-null at construction so downstream payload code
 * can fail fast rather than NPE deep in JSON serialisation.
 */
public final class ClassifierResult {
    public final VehicleState state;
    public final Confidence confidence;
    public final List<String> reasons;

    public ClassifierResult(VehicleState state, Confidence confidence, List<String> reasons) {
        this.state = Objects.requireNonNull(state, "state");
        this.confidence = Objects.requireNonNull(confidence, "confidence");
        this.reasons = (reasons == null) ? Collections.emptyList() : List.copyOf(reasons);
    }
}
