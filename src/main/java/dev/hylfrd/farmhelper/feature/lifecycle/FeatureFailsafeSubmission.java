package dev.hylfrd.farmhelper.feature.lifecycle;

import dev.hylfrd.farmhelper.failsafe.FailsafeArbitrator;
import dev.hylfrd.farmhelper.failsafe.FailsafeCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit result of applying feature policy before domain failsafe arbitration. */
public record FeatureFailsafeSubmission(
        Status status,
        FailsafeCandidate candidate,
        List<FeatureId> suppressors,
        Optional<FailsafeArbitrator.Submission> arbitration
) {
    public enum Status {
        INACTIVE,
        SUPPRESSED,
        ARBITRATED
    }

    public FeatureFailsafeSubmission {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(candidate, "candidate");
        suppressors = List.copyOf(Objects.requireNonNull(suppressors, "suppressors"));
        Objects.requireNonNull(arbitration, "arbitration");
        if (status == Status.SUPPRESSED && suppressors.isEmpty()) {
            throw new IllegalArgumentException("suppressed submission requires a suppressor");
        }
        if (status != Status.SUPPRESSED && !suppressors.isEmpty()) {
            throw new IllegalArgumentException("only a suppressed submission may name suppressors");
        }
        if ((status == Status.ARBITRATED) != arbitration.isPresent()) {
            throw new IllegalArgumentException("only an arbitrated submission may carry arbitration");
        }
    }

    public boolean accepted() {
        return arbitration.map(FailsafeArbitrator.Submission::accepted).orElse(false);
    }
}
