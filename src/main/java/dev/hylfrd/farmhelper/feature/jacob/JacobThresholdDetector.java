package dev.hylfrd.farmhelper.feature.jacob;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Pure stateful threshold policy over immutable parsed evidence.
 *
 * <p>Within one contest identity, accepted counts are monotonic. A lower count or older sequence
 * is stale and leaves the last trusted decision intact. A different identity starts a fresh
 * contest, so a prior contest cannot carry a trigger into the next one.
 */
public final class JacobThresholdDetector {
    private final JacobThresholds thresholds;
    private JacobThresholdSnapshot snapshot = JacobThresholdSnapshot.empty();

    public JacobThresholdDetector(JacobThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    public synchronized JacobThresholdSnapshot snapshot() {
        return snapshot;
    }

    public synchronized JacobThresholdResult observe(JacobEvidenceParseResult parsed) {
        Objects.requireNonNull(parsed, "parsed");
        if (parsed.sequence() <= snapshot.sequence()) {
            return new JacobThresholdResult(JacobThresholdUpdate.STALE, snapshot);
        }

        if (parsed.evidence().isAbsent()) {
            snapshot = new JacobThresholdSnapshot(
                    parsed.sequence(),
                    Observation.absent(),
                    Observation.absent(),
                    Observation.absent(),
                    JacobThresholdStatus.NO_CONTEST,
                    JacobEvidenceIssue.NONE);
            return accepted();
        }

        if (parsed.evidence().isUnknown()) {
            snapshot = unknownSnapshot(
                    parsed.sequence(), parsed.contest(), parsed.issue());
            return rejected();
        }

        JacobContestEvidence current = parsed.evidence().get();
        JacobContestEvidence previous = retainedEvidence(current.contest());
        if (previous != null) {
            if (previous.crop() != current.crop()) {
                snapshot = unknownSnapshot(
                        parsed.sequence(), current.contest(), JacobEvidenceIssue.CONFLICT);
                return rejected();
            }
            if (current.collectedCount() < previous.collectedCount()) {
                return new JacobThresholdResult(JacobThresholdUpdate.STALE, snapshot);
            }
        }

        OptionalLong threshold = thresholds.thresholdFor(current.crop());
        if (threshold.isEmpty()) {
            snapshot = new JacobThresholdSnapshot(
                    parsed.sequence(),
                    Observation.present(current.contest()),
                    Observation.present(current),
                    Observation.unknown(),
                    JacobThresholdStatus.UNKNOWN,
                    JacobEvidenceIssue.THRESHOLD_UNAVAILABLE);
            return rejected();
        }

        JacobThresholdStatus status = current.collectedCount() >= threshold.getAsLong()
                ? JacobThresholdStatus.TRIGGERED
                : JacobThresholdStatus.WAITING;
        snapshot = new JacobThresholdSnapshot(
                parsed.sequence(),
                Observation.present(current.contest()),
                Observation.present(current),
                Observation.present(threshold.getAsLong()),
                status,
                JacobEvidenceIssue.NONE);
        return accepted();
    }

    public synchronized void reset() {
        snapshot = JacobThresholdSnapshot.empty();
    }

    private JacobContestEvidence retainedEvidence(JacobContestIdentity contest) {
        if (!snapshot.evidence().isPresent() || !snapshot.evidence().get().contest().equals(contest)) {
            return null;
        }
        return snapshot.evidence().get();
    }

    private JacobThresholdSnapshot unknownSnapshot(
            long sequence,
            JacobContestIdentity contest,
            JacobEvidenceIssue issue
    ) {
        Observation<JacobContestEvidence> evidence = snapshot.evidence();
        Observation<Long> threshold = snapshot.threshold();
        if (!evidence.isPresent() || !evidence.get().contest().equals(contest)) {
            evidence = Observation.absent();
            threshold = Observation.unknown();
        }
        return new JacobThresholdSnapshot(
                sequence,
                Observation.present(contest),
                evidence,
                threshold,
                JacobThresholdStatus.UNKNOWN,
                issue);
    }

    private JacobThresholdResult accepted() {
        return new JacobThresholdResult(JacobThresholdUpdate.ACCEPTED, snapshot);
    }

    private JacobThresholdResult rejected() {
        return new JacobThresholdResult(JacobThresholdUpdate.REJECTED, snapshot);
    }
}
