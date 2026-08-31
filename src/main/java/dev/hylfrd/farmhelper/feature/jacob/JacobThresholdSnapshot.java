package dev.hylfrd.farmhelper.feature.jacob;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Immutable reader view of the last accepted Jacob threshold state. */
public record JacobThresholdSnapshot(
        long sequence,
        Observation<JacobContestIdentity> contest,
        Observation<JacobContestEvidence> evidence,
        Observation<Long> threshold,
        JacobThresholdStatus status,
        JacobEvidenceIssue issue
) {
    public JacobThresholdSnapshot {
        Objects.requireNonNull(contest, "contest");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issue, "issue");
        if (sequence < -1L) {
            throw new IllegalArgumentException("sequence must be -1 or non-negative");
        }
        if (threshold.isPresent() && threshold.get() < 0L) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        if (evidence.isPresent()) {
            JacobContestEvidence value = evidence.get();
            if (contest.isPresent() && !contest.get().equals(value.contest())) {
                throw new IllegalArgumentException("evidence contest does not match snapshot contest");
            }
        }

        switch (status) {
            case NO_CONTEST -> {
                if (!contest.isAbsent() || !evidence.isAbsent() || !threshold.isAbsent()
                        || issue != JacobEvidenceIssue.NONE) {
                    throw new IllegalArgumentException("NO_CONTEST snapshot has unexpected evidence");
                }
            }
            case WAITING, TRIGGERED -> {
                if (!contest.isPresent() || !evidence.isPresent() || !threshold.isPresent()
                        || issue != JacobEvidenceIssue.NONE) {
                    throw new IllegalArgumentException("actionable snapshot is incomplete");
                }
                JacobContestEvidence value = evidence.get();
                if (value.sequence() != sequence) {
                    throw new IllegalArgumentException("actionable evidence sequence does not match snapshot");
                }
                boolean reached = value.collectedCount() >= threshold.get();
                if ((status == JacobThresholdStatus.TRIGGERED) != reached) {
                    throw new IllegalArgumentException("snapshot status does not match inclusive threshold rule");
                }
            }
            case UNKNOWN -> {
                if (issue == JacobEvidenceIssue.NONE) {
                    throw new IllegalArgumentException("UNKNOWN snapshot requires an issue");
                }
            }
        }
    }

    public static JacobThresholdSnapshot empty() {
        return new JacobThresholdSnapshot(
                -1L,
                Observation.absent(),
                Observation.absent(),
                Observation.absent(),
                JacobThresholdStatus.NO_CONTEST,
                JacobEvidenceIssue.NONE);
    }

    public boolean triggered() {
        return status == JacobThresholdStatus.TRIGGERED;
    }

    public boolean failClosed() {
        return status == JacobThresholdStatus.UNKNOWN;
    }
}
