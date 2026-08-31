package dev.hylfrd.farmhelper.navigation.simulation;

import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;

import java.util.List;
import java.util.Objects;

/** Read-only world queries required by the pure predictor. */
public interface FlyStoppingWorldPort {
    /**
     * Returns all collision boxes intersecting the query. A known empty list means the complete
     * query is clear; missing, stale, or unloaded data must be returned as unknown evidence.
     */
    FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query);

    /** Returns the raw slipperiness below a state that began its tick on the ground. */
    FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state);

    static FlyStoppingWorldPort knownEmpty(double slipperiness) {
        if (!Double.isFinite(slipperiness) || slipperiness < 0.0D) {
            throw new IllegalArgumentException("slipperiness must be finite and non-negative");
        }
        return new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                Objects.requireNonNull(query, "query");
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                Objects.requireNonNull(state, "state");
                return FlyStoppingEvidence.known(slipperiness);
            }
        };
    }
}
