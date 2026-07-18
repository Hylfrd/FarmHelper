package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationWorkTicket;
import dev.hylfrd.farmhelper.navigation.SpaceEvidenceReason;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable route or deterministic failure evidence from one exact SEARCHING work ticket. */
public record PathSearchResult(
        NavigationWorkTicket workTicket,
        PathSearchOutcome outcome,
        List<PathNode> path,
        int expandedNodes,
        int expansionBudget,
        double maxDistance,
        long elapsedMillis,
        Set<SpaceEvidenceReason> rejectedEvidence
) {
    public PathSearchResult {
        Objects.requireNonNull(workTicket, "workTicket");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(rejectedEvidence, "rejectedEvidence");
        if (workTicket.phase() != NavigationPhase.SEARCHING) {
            throw new IllegalArgumentException("path result requires a SEARCHING work ticket");
        }
        path = List.copyOf(path);
        rejectedEvidence = rejectedEvidence.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(rejectedEvidence));
        if (expandedNodes < 0 || expansionBudget <= 0 || expandedNodes > expansionBudget) {
            throw new IllegalArgumentException("expanded-node counts are outside the search budget");
        }
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D
                || maxDistance > FlyPathSearch.MAX_DISTANCE) {
            throw new IllegalArgumentException("maxDistance is outside the logical ceiling");
        }
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis must be non-negative");
        }
        boolean validPath = switch (outcome) {
            case FOUND -> path.size() >= 1;
            case PARTIAL -> path.size() >= 2;
            case ALREADY_AT_GOAL -> path.size() == 1;
            case NO_PATH, TIMEOUT, BUDGET_EXHAUSTED -> path.isEmpty();
        };
        if (!validPath) {
            throw new IllegalArgumentException("path does not agree with the search outcome");
        }
    }

    public boolean hasRoute() {
        return switch (outcome) {
            case FOUND, PARTIAL, ALREADY_AT_GOAL -> true;
            case NO_PATH, TIMEOUT, BUDGET_EXHAUSTED -> false;
        };
    }
}
