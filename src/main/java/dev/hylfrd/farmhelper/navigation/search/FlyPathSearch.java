package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.navigation.SegmentedSpatialSnapshot;
import dev.hylfrd.farmhelper.navigation.SpaceEvidence;
import dev.hylfrd.farmhelper.navigation.SpaceEvidenceReason;
import dev.hylfrd.farmhelper.navigation.Traversability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Pure bounded A* search preserving the upstream flight-node order and closest-partial behavior.
 *
 * <p>The caller supplies an immutable segmented capture. Search never reads Minecraft state and
 * never starts a background thread. Equal priorities follow vanilla Path's strict-comparison,
 * non-FIFO heap behavior while preserving DOWN/UP/NORTH/SOUTH/WEST/EAST discovery order.</p>
 */
public final class FlyPathSearch {
    public static final double MAX_DISTANCE = 1_500.0D;
    public static final long TIMEOUT_MILLIS = 10_000L;
    public static final long TIMEOUT_NANOS =
            TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
    public static final int MAX_EXPANDED_NODES =
            SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS;

    private static final List<Offset> OFFSETS = List.of(
            new Offset(0, -1, 0),
            new Offset(0, 1, 0),
            new Offset(0, 0, -1),
            new Offset(0, 0, 1),
            new Offset(-1, 0, 0),
            new Offset(1, 0, 0));

    private final LongSupplier nanoTicker;
    private final int hardExpansionLimit;

    public FlyPathSearch() {
        this(System::nanoTime, MAX_EXPANDED_NODES);
    }

    /**
     * Testing seam whose supplier returns monotonic nanosecond ticks, with System.nanoTime wrap
     * semantics. Values are durations only and are never interpreted as wall-clock timestamps.
     */
    FlyPathSearch(LongSupplier nanoTicker, int hardExpansionLimit) {
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker");
        if (hardExpansionLimit <= 0 || hardExpansionLimit > MAX_EXPANDED_NODES) {
            throw new IllegalArgumentException("hard expansion limit is outside the bounded budget");
        }
        this.hardExpansionLimit = hardExpansionLimit;
    }

    public PathSearchResult search(PathSearchRequest request) {
        Objects.requireNonNull(request, "request");
        long startedAt = nanoTicker.getAsLong();
        PathNode start = request.startNode();
        PathNode target = request.targetNode();
        float directDistance = request.directDistanceToGoal();
        float maxDistance = (float) Math.min(
                directDistance + 5.0D, MAX_DISTANCE);
        int expansionBudget = Math.min(
                hardExpansionLimit, Math.max(1, request.spatialSnapshot().capturedCellCount()));
        if (directDistance < 1.0D) {
            return result(request, PathSearchOutcome.ALREADY_AT_GOAL, List.of(start),
                    0, expansionBudget, maxDistance, elapsedNanosSince(startedAt), Set.of());
        }

        UpstreamOpenHeap open = new UpstreamOpenHeap();
        Map<PathNode, NodeState> states = new HashMap<>();
        EnumSet<SpaceEvidenceReason> rejectedEvidence =
                EnumSet.noneOf(SpaceEvidenceReason.class);
        NodeState startState = new NodeState(start);
        startState.pathCost = 0.0F;
        startState.distanceToNext = start.squaredDistanceTo(target);
        startState.priority = startState.distanceToNext;
        states.put(start, startState);
        open.add(startState);
        PathNode closest = start;
        float closestDistanceSquared = start.squaredDistanceTo(target);
        int expanded = 0;

        while (!open.isEmpty()) {
            long elapsedNanos = elapsedNanosSince(startedAt);
            if (elapsedNanos >= TIMEOUT_NANOS) {
                return timeoutResult(request, expanded, expansionBudget, maxDistance,
                        elapsedNanos, rejectedEvidence);
            }
            if (expanded >= expansionBudget) {
                return result(request, PathSearchOutcome.BUDGET_EXHAUSTED, List.of(),
                        expanded, expansionBudget, maxDistance, elapsedNanos, rejectedEvidence);
            }

            NodeState currentState = open.dequeue();
            currentState.closed = true;
            expanded++;

            PathNode current = currentState.node;
            float currentDistanceSquared = current.squaredDistanceTo(target);
            if (currentDistanceSquared < closestDistanceSquared) {
                closest = current;
                closestDistanceSquared = currentDistanceSquared;
            }
            if (current.equals(target)) {
                return result(request, PathSearchOutcome.FOUND, pathTo(current, states),
                        expanded, expansionBudget, maxDistance, elapsedNanos, rejectedEvidence);
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
                float targetDistance = neighbor.distanceTo(target);
                if (!(targetDistance < maxDistance)) {
                    continue;
                }

                NodeState known = states.get(neighbor);
                if (known != null && known.closed) {
                    continue;
                }
                float candidateCost =
                        currentState.pathCost + current.squaredDistanceTo(neighbor);
                if (!(candidateCost < maxDistance * 2.0F)
                        || known != null && known.isAssigned()
                        && !(candidateCost < known.pathCost)) {
                    continue;
                }
                if (known == null) {
                    known = new NodeState(neighbor);
                    states.put(neighbor, known);
                }
                known.predecessor = current;
                known.pathCost = candidateCost;
                known.distanceToNext = neighbor.squaredDistanceTo(target);
                float priority = known.pathCost + known.distanceToNext;
                if (known.isAssigned()) {
                    open.changeDistance(known, priority);
                } else {
                    known.priority = priority;
                    open.add(known);
                }
            }
        }

        long elapsedNanos = elapsedNanosSince(startedAt);
        if (elapsedNanos >= TIMEOUT_NANOS) {
            return timeoutResult(request, expanded, expansionBudget, maxDistance,
                    elapsedNanos, rejectedEvidence);
        }
        if (closest.equals(start)) {
            return result(request, PathSearchOutcome.NO_PATH, List.of(),
                    expanded, expansionBudget, maxDistance, elapsedNanos, rejectedEvidence);
        }
        return result(request, PathSearchOutcome.PARTIAL, pathTo(closest, states),
                expanded, expansionBudget, maxDistance, elapsedNanos, rejectedEvidence);
    }

    private PathSearchResult timeoutResult(
            PathSearchRequest request,
            int expanded,
            int expansionBudget,
            float maxDistance,
            long elapsedNanos,
            Set<SpaceEvidenceReason> rejectedEvidence
    ) {
        return result(request, PathSearchOutcome.TIMEOUT, List.of(), expanded,
                expansionBudget, maxDistance, elapsedNanos, rejectedEvidence);
    }

    private PathSearchResult result(
            PathSearchRequest request,
            PathSearchOutcome outcome,
            List<PathNode> path,
            int expanded,
            int expansionBudget,
            float maxDistance,
            long elapsedNanos,
            Set<SpaceEvidenceReason> rejectedEvidence
    ) {
        return new PathSearchResult(
                request.workTicket(), outcome, path, expanded, expansionBudget,
                maxDistance, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), rejectedEvidence);
    }

    private long elapsedNanosSince(long startedAt) {
        long elapsed = nanoTicker.getAsLong() - startedAt;
        return elapsed < 0L ? 0L : elapsed;
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

    static final class NodeState {
        final PathNode node;
        private float pathCost;
        private float distanceToNext;
        private float priority;
        private PathNode predecessor;
        private boolean closed;
        private int heapIndex = -1;

        NodeState(PathNode node) {
            this.node = Objects.requireNonNull(node, "node");
        }

        NodeState(PathNode node, float priority) {
            this(node);
            this.priority = priority;
        }

        boolean isAssigned() {
            return heapIndex >= 0;
        }
    }

    /** Exact strict-comparison binary heap used by vanilla 1.8.9 Path. */
    static final class UpstreamOpenHeap {
        private final List<NodeState> points = new ArrayList<>();

        boolean isEmpty() {
            return points.isEmpty();
        }

        void add(NodeState point) {
            Objects.requireNonNull(point, "point");
            if (point.isAssigned()) {
                throw new IllegalStateException("path node is already assigned");
            }
            point.heapIndex = points.size();
            points.add(point);
            sortBack(point.heapIndex);
        }

        NodeState dequeue() {
            if (points.isEmpty()) {
                throw new IllegalStateException("path heap is empty");
            }
            NodeState first = points.get(0);
            NodeState last = points.remove(points.size() - 1);
            if (!points.isEmpty()) {
                points.set(0, last);
                last.heapIndex = 0;
                sortForward(0);
            }
            first.heapIndex = -1;
            return first;
        }

        void changeDistance(NodeState point, float priority) {
            Objects.requireNonNull(point, "point");
            if (!point.isAssigned()) {
                throw new IllegalArgumentException("path node is not assigned");
            }
            float previous = point.priority;
            point.priority = priority;
            if (priority < previous) {
                sortBack(point.heapIndex);
            } else {
                sortForward(point.heapIndex);
            }
        }

        private void sortBack(int index) {
            NodeState point = points.get(index);
            float priority = point.priority;
            while (index > 0) {
                int parentIndex = index - 1 >> 1;
                NodeState parent = points.get(parentIndex);
                if (priority >= parent.priority) {
                    break;
                }
                points.set(index, parent);
                parent.heapIndex = index;
                index = parentIndex;
            }
            points.set(index, point);
            point.heapIndex = index;
        }

        private void sortForward(int index) {
            NodeState point = points.get(index);
            float priority = point.priority;
            while (true) {
                int leftIndex = 1 + (index << 1);
                int rightIndex = leftIndex + 1;
                if (leftIndex >= points.size()) {
                    break;
                }
                NodeState left = points.get(leftIndex);
                float leftPriority = left.priority;
                NodeState right;
                float rightPriority;
                if (rightIndex >= points.size()) {
                    right = null;
                    rightPriority = Float.POSITIVE_INFINITY;
                } else {
                    right = points.get(rightIndex);
                    rightPriority = right.priority;
                }

                NodeState selected;
                int selectedIndex;
                float selectedPriority;
                if (leftPriority < rightPriority) {
                    selected = left;
                    selectedIndex = leftIndex;
                    selectedPriority = leftPriority;
                } else {
                    selected = right;
                    selectedIndex = rightIndex;
                    selectedPriority = rightPriority;
                }
                if (selectedPriority >= priority) {
                    break;
                }
                points.set(index, selected);
                selected.heapIndex = index;
                index = selectedIndex;
            }
            points.set(index, point);
            point.heapIndex = index;
        }
    }
}
