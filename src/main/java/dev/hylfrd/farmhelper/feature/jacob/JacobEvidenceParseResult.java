package dev.hylfrd.farmhelper.feature.jacob;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Immutable tri-state result at the cleaned-text observation boundary. */
public record JacobEvidenceParseResult(
        JacobContestIdentity contest,
        long sequence,
        Observation<JacobContestEvidence> evidence,
        JacobEvidenceIssue issue
) {
    public JacobEvidenceParseResult {
        Objects.requireNonNull(contest, "contest");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(issue, "issue");
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (evidence.isPresent()) {
            JacobContestEvidence value = evidence.get();
            if (!value.contest().equals(contest) || value.sequence() != sequence) {
                throw new IllegalArgumentException("evidence identity does not match parse context");
            }
            if (issue != JacobEvidenceIssue.NONE) {
                throw new IllegalArgumentException("present evidence cannot have an issue");
            }
        } else if (evidence.isAbsent()) {
            if (issue != JacobEvidenceIssue.NONE) {
                throw new IllegalArgumentException("absent evidence cannot have an issue");
            }
        } else if (issue == JacobEvidenceIssue.NONE) {
            throw new IllegalArgumentException("unknown evidence must have an issue");
        }
    }

    public static JacobEvidenceParseResult present(JacobContestEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return new JacobEvidenceParseResult(
                evidence.contest(), evidence.sequence(), Observation.present(evidence), JacobEvidenceIssue.NONE);
    }

    public static JacobEvidenceParseResult absent(JacobContestIdentity contest, long sequence) {
        return new JacobEvidenceParseResult(contest, sequence, Observation.absent(), JacobEvidenceIssue.NONE);
    }

    public static JacobEvidenceParseResult unknown(
            JacobContestIdentity contest,
            long sequence,
            JacobEvidenceIssue issue
    ) {
        if (issue == null || issue == JacobEvidenceIssue.NONE) {
            throw new IllegalArgumentException("unknown evidence requires a non-NONE issue");
        }
        return new JacobEvidenceParseResult(contest, sequence, Observation.unknown(), issue);
    }
}
