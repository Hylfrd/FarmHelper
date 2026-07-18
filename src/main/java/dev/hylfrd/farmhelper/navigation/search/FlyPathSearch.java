package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.navigation.SegmentedSpatialSnapshot;
import dev.hylfrd.farmhelper.navigation.SpaceEvidence;
import dev.hylfrd.farmhelper.navigation.SpaceEvidenceReason;
import dev.hylfrd.farmhelper.navigation.Traversability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Pure bounded A* search preserving the upstream flight-node order and closest-partial behavior.
 *
 * <p>The caller supplies an immutable segmented capture. Search never reads Minecraft state and
 * never starts a background thread. Equal priorities retain discovery order, which makes the
 * upstream DOWN/UP/NORTH/SOUTH/WEST/EAST tie behavior repeatable.</p>
 */
public final class FlyPathSearch {
    public static final double MAX_DISTANCE = 1_500.0D;
    public static final long TIMEOUT_MILLIS = 10_000L;
    public static final int MAX_EXPANDED_NODES =
            SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS;

    private static final List<Offset> OFFSETS = List.of(
            new Offset(0, -1, 0),
            new Offset(0, 1, 0),
            new Offset(0, 0, -1),
            new Offset(0, 0, 1),
            new Offset(-1, 0, 0),
            new Offset(1, 0, 0));

    private static final Comparator<OpenEntry> OPEN_ORDER =
            Comparator.comparingDouble(OpenEntry::priority)
                    .thenComparingLong(OpenEntry::sequence);

    private final LongSupplier millisecondClock;
    private final int hardExpansionLimit;

    public FlyPathSearch() {
        this(System::currentTimeMillis, MAX_EXPANDED_NODES);
    }

    FlyPathSearch(LongSupplier millisecondClock, int hardExpansionLimit) {
        this.millisecondClock = Objects.requireNonNull(millisecondClock, "millisecondClock");
        if (hardExpansionLimit <= 0 || hardExpansionLimit > MAX_EXPANDED_NODES) {
            throw new IllegalArgumentException("hard expansion limit is outside the bounded budget");
        }
        this.hardExpansionLimit = hardExpansionLimit;
    }

    public PathSearchResult search(PathSearchRequest request) {
        Objects.requireNonNull(request, "request");
        long startedAt = millisecondClock.getAsLong();
        PathNode start = request.startNode();
        PathNode target = request.targetNode();
        double directDistance = request.directDistanceToGoal();
        float maxDistance = (float) Math.min(
                directDistance + 5.0D, MAX_DISTANCE);
        int expansionBudget = Math.min(
                hardExpansionLimit, Math.max(1, request.spatialSnapshot().capturedCellCount()));
        if (directDistance < 1.0D) {
            return result(request, PathSearchOutcome.ALREADY_AT_GOAL, List.of(start),
                    0, expansionBudget, maxDistance, elapsedSince(startedAt), Set.of());
        }

        PriorityQueue<OpenEntry> open = new PriorityQueue<>(OPEN_ORDER);
        Map<PathNode, NodeState> states = new HashMap<>();
        EnumSet<SpaceEvidenceReason> rejectedEvidence =
                EnumSet.noneOf(SpaceEvidenceReason.class);
        NodeState startState = new NodeState(0.0F, null);
        states.put(start, startState);
        long sequence = 0L;
        open.add(new OpenEntry(start, 0.0F, heuristic(start, target), sequence++));
        PathNode closest = start;
        float closestDistanceSquared = squaredDistance(start, target);
        int expanded = 0;

        while (!open.isEmpty()) {
            long elapsed = elapsedSince(startedAt);
            if (elapsed >= TIMEOUT_MILLIS) {
                return new PathSearchResult(
                        request.workTicket(), PathSearchOutcome.TIMEOUT, List.of(),
                        expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
            }
            if (expanded >= expansionBudget) {
                return result(request, PathSearchOutcome.BUDGET_EXHAUSTED, List.of(),
                        expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
            }

            OpenEntry entry = open.remove();
            NodeState currentState = states.get(entry.node());
            if (currentState == null || currentState.closed
                    || Float.compare(entry.pathCost(), currentState.pathCost) != 0) {
                continue;
            }
            currentState.closed = true;
            expanded++;

            PathNode current = entry.node();
            float currentDistance = heuristic(current, target);
            float currentDistanceSquared = squaredDistance(current, target);
            if (currentDistanceSquared < closestDistanceSquared) {
                closest = current;
                closestDistanceSquared = currentDistanceSquared;
            }
            if (current.equals(target)) {
                return result(request, PathSearchOutcome.FOUND, pathTo(current, states),
                        expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
            }

            // Vanilla PathFinder does not ask its NodeProcessor for options outside this radius.
            if (!(currentDistance < maxDistance)) {
                continue;
            }
            for (Offset offset : OFFSETS) {
                PathNode neighbor;
                try {
                    neighbor = current.translated(offset.x(), offset.y(), offset.z());
                } catch (ArithmeticException exception) {
                    rejectedEvidence.add(SpaceEvidenceReason.QUERY_TOO_LARGE);
                    continue;
                }

                SpaceEvidence evidence = Traversability.evaluate(
                        request.spatialSnapshot(),
                        request.captureTicket(),
                        request.bodyAt(neighbor),
                        request.mode());
                if (!evidence.traversable()) {
                    rejectedEvidence.add(evidence.reason());
                    continue;
                }
                float targetDistance = heuristic(neighbor, target);
                if (!(targetDistance < maxDistance)) {
                    continue;
                }

                float candidateCost = currentState.pathCost + 1.0F;
                if (!(candidateCost < maxDistance)) {
                    continue;
                }
                NodeState known = states.get(neighbor);
                if (known != null && (known.closed || !(candidateCost < known.pathCost))) {
                    continue;
                }
                if (known == null) {
                    known = new NodeState(candidateCost, current);
                    states.put(neighbor, known);
                } else {
                    known.pathCost = candidateCost;
                    known.predecessor = current;
                }
                open.add(new OpenEntry(
                        neighbor, candidateCost, candidateCost + targetDistance, sequence++));
            }
        }

        long elapsed = elapsedSince(startedAt);
        if (elapsed >= TIMEOUT_MILLIS) {
            return new PathSearchResult(
                    request.workTicket(), PathSearchOutcome.TIMEOUT, List.of(),
                    expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
        }
        if (closest.equals(start)) {
            return result(request, PathSearchOutcome.NO_PATH, List.of(),
                    expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
        }
        return result(request, PathSearchOutcome.PARTIAL, pathTo(closest, states),
                expanded, expansionBudget, maxDistance, elapsed, rejectedEvidence);
    }

    private PathSearchResult result(
            PathSearchRequest request,
            PathSearchOutcome outcome,
            List<PathNode> path,
            int expanded,
            int expansionBudget,
            float maxDistance,
            long elapsed,
            Set<SpaceEvidenceReason> rejectedEvidence
    ) {
        return new PathSearchResult(
                request.workTicket(), outcome, path, expanded, expansionBudget,
                maxDistance, elapsed, rejectedEvidence);
    }

    private long elapsedSince(long startedAt) {
        long now = millisecondClock.getAsLong();
        if (now <= startedAt) {
            return 0L;
        }
        try {
            return Math.subtractExact(now, startedAt);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static float heuristic(PathNode from, PathNode to) {
        return (float) from.distanceTo(to);
    }

    private static float squaredDistance(PathNode from, PathNode to) {
        return (float) from.squaredDistanceTo(to);
    }

    private static List<PathNode> pathTo(
            PathNode target,
            Map<PathNode, NodeState> states
    ) {
        ArrayDeque<PathNode> reversed = new ArrayDeque<>();
        PathNode current = target;
        while (current != null) {
            reversed.addFirst(current);
            NodeState state = states.get(current);
            current = state == null ? null : state.predecessor;
        }
        return List.copyOf(new ArrayList<>(reversed));
    }

    private record Offset(int x, int y, int z) {
    }

    private record OpenEntry(
            PathNode node,
            float pathCost,
            float priority,
            long sequence
    ) {
    }

    private static final class NodeState {
        private float pathCost;
        private PathNode predecessor;
        private boolean closed;

        private NodeState(float pathCost, PathNode predecessor) {
            this.pathCost = pathCost;
            this.predecessor = predecessor;
        }
    }
}
