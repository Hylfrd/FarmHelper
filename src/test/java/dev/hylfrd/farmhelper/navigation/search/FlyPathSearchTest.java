package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationMode;
import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationTicket;
import dev.hylfrd.farmhelper.navigation.NavigationWorkTicket;
import dev.hylfrd.farmhelper.navigation.SegmentedSpatialSnapshot;
import dev.hylfrd.farmhelper.navigation.SpaceEvidenceReason;
import dev.hylfrd.farmhelper.navigation.SpatialSegment;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlyPathSearchTest {
    private static final long EPOCH = 41L;
    private static final NavigationTicket RUN =
            new NavigationTicket(new ControlOwner("search-test"), 7L, EPOCH);
    private static final NavigationWorkTicket CAPTURE =
            new NavigationWorkTicket(RUN, NavigationPhase.CAPTURING, 2L);
    private static final NavigationWorkTicket SEARCH =
            new NavigationWorkTicket(RUN, NavigationPhase.SEARCHING, 3L);
    private static final ResourceIdentifier EMPTY_FLUID =
            ResourceIdentifier.parse("minecraft:empty");

    @Test
    void findsSixAxisRouteUsingUpstreamStartAndTargetDiscretization() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 10, 8, 8);
        PathSearchRequest request = request(
                snapshot(logical, segment(0, 10L, expanded(logical), allAir(expanded(logical)))),
                new BoxSnapshot(2.2D, 2.0D, 3.2D, 2.8D, 3.8D, 3.8D),
                new NavigationGoal(6.6D, 2.9D, 3.5D));

        assertEquals(new PathNode(2, 2, 3), request.startNode());
        assertEquals(new PathNode(6, 2, 3), request.targetNode());
        PathSearchResult result = new FlyPathSearch().search(request);

        assertEquals(PathSearchOutcome.FOUND, result.outcome());
        assertEquals(List.of(
                new PathNode(2, 2, 3),
                new PathNode(3, 2, 3),
                new PathNode(4, 2, 3),
                new PathNode(5, 2, 3),
                new PathNode(6, 2, 3)), result.path());
        assertEquals(SEARCH, result.workTicket());
        assertTrue(result.rejectedEvidence().isEmpty());
    }

    @Test
    void preservesStrictAlreadyAtDestinationCheckBeforeEqualNodeSearch() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 6, 6, 6);
        BoxSnapshot capture = expanded(logical);
        SegmentedSpatialSnapshot spatial =
                snapshot(logical, segment(0, 10_001L, capture, allAir(capture)));
        BoxSnapshot body = new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D);

        PathSearchResult already = new FlyPathSearch().search(request(
                spatial, body, new NavigationGoal(2.3D, 2.0D, 2.3D)));
        assertEquals(PathSearchOutcome.ALREADY_AT_GOAL, already.outcome());
        assertEquals(0, already.expandedNodes());

        PathSearchRequest exactThreshold = request(
                spatial, body, new NavigationGoal(2.4D, 3.0D, 2.4D));
        assertEquals(exactThreshold.startNode(), exactThreshold.targetNode());
        PathSearchResult searched = new FlyPathSearch().search(exactThreshold);
        assertEquals(PathSearchOutcome.FOUND, searched.outcome());
        assertEquals(List.of(exactThreshold.startNode()), searched.path());
        assertEquals(1, searched.expandedNodes());
    }

    @Test
    void stableTieOrderChoosesDownBeforeEquivalentDetours() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 8, 8, 8);
        BoxSnapshot capture = expanded(logical);
        Map<BlockPosition, Observation<BlockStateSnapshot>> cells =
                new LinkedHashMap<>(allAir(capture));
        cells.put(new BlockPosition(3, 4, 3), Observation.present(solid()));
        PathSearchRequest request = request(
                snapshot(logical, segment(0, 11L, capture, Map.copyOf(cells))),
                new BoxSnapshot(2, 3, 3, 2.6D, 4.8D, 3.6D),
                new NavigationGoal(5.3D, 3.9D, 3.3D));

        List<PathNode> expected = new FlyPathSearch().search(request).path();
        assertEquals(PathSearchOutcome.FOUND, new FlyPathSearch().search(request).outcome());
        for (int run = 0; run < 20; run++) {
            assertEquals(expected, new FlyPathSearch().search(request).path());
        }
        assertEquals(new PathNode(2, 2, 3), expected.get(1));
    }

    @Test
    void returnsClosestPartialRouteAndNoPathUsingUpstreamExitSemantics() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 8, 6, 6);
        BoxSnapshot capture = expanded(logical);
        Map<BlockPosition, Observation<BlockStateSnapshot>> partialCells =
                new LinkedHashMap<>(allAir(capture));
        for (int y = 0; y < 6; y++) {
            for (int z = 0; z < 6; z++) {
                partialCells.put(new BlockPosition(5, y, z), Observation.present(solid()));
            }
        }
        PathSearchRequest partialRequest = request(
                snapshot(logical, segment(0, 12L, capture, Map.copyOf(partialCells))),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(7.3D, 2.9D, 2.3D));
        PathSearchResult partial = new FlyPathSearch().search(partialRequest);
        assertEquals(PathSearchOutcome.PARTIAL, partial.outcome());
        assertTrue(partial.path().size() >= 2);
        assertTrue(partial.path().get(partial.path().size() - 1)
                .distanceTo(partialRequest.targetNode())
                < partialRequest.startNode().distanceTo(partialRequest.targetNode()));

        Map<BlockPosition, Observation<BlockStateSnapshot>> trapped =
                new LinkedHashMap<>(allAir(capture));
        for (PathNode node : List.of(
                new PathNode(2, 1, 2), new PathNode(2, 3, 2),
                new PathNode(2, 2, 1), new PathNode(2, 2, 3),
                new PathNode(1, 2, 2), new PathNode(3, 2, 2))) {
            trapped.put(new BlockPosition(node.x(), node.y(), node.z()),
                    Observation.present(solid()));
        }
        PathSearchResult none = new FlyPathSearch().search(request(
                snapshot(logical, segment(0, 13L, capture, Map.copyOf(trapped))),
                partialRequest.playerBody(), partialRequest.goal()));
        assertEquals(PathSearchOutcome.NO_PATH, none.outcome());
        assertTrue(none.path().isEmpty());
        assertTrue(none.rejectedEvidence().contains(SpaceEvidenceReason.COLLISION));
    }

    @Test
    void unknownUnloadedFluidAndCollisionErrorEvidenceAreBlocked() {
        assertBlockedBy(Observation.absent(), SpaceEvidenceReason.MISSING_EVIDENCE);
        assertBlockedBy(Observation.unknown(), SpaceEvidenceReason.UNKNOWN_EVIDENCE);
        assertBlockedBy(Observation.present(water()), SpaceEvidenceReason.FLUID_OBSTRUCTION);
        assertBlockedBy(Observation.present(collisionError()),
                SpaceEvidenceReason.COLLISION_ERROR);

        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 6, 6, 6);
        BoxSnapshot capture = expanded(logical);
        SpatialSnapshot unloaded = new SpatialSnapshot(
                EPOCH, 21L, capture,
                (int) capture.minY(), (int) capture.maxY(),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                Map.of(new ChunkPosition(0, 0),
                        new ChunkSnapshot(new ChunkPosition(0, 0), false, Map.of())));
        PathSearchResult result = new FlyPathSearch().search(request(
                new SegmentedSpatialSnapshot(CAPTURE, logical,
                        List.of(new SpatialSegment(0, CAPTURE, 21L, unloaded))),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4.3D, 2.9D, 2.3D)));
        assertEquals(PathSearchOutcome.NO_PATH, result.outcome());
        assertTrue(result.rejectedEvidence().contains(SpaceEvidenceReason.UNLOADED));
    }

    @Test
    void conflictingOverlapEvidenceIsBlockedDeterministically() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 6, 6, 6);
        BoxSnapshot left = new BoxSnapshot(-2, -2, -2, 4, 8, 8);
        BoxSnapshot right = new BoxSnapshot(2, -2, -2, 8, 8, 8);
        Map<BlockPosition, Observation<BlockStateSnapshot>> conflicting =
                new LinkedHashMap<>(allAir(right));
        for (BlockPosition position : List.copyOf(conflicting.keySet())) {
            if (position.x() == 2 || position.x() == 3) {
                conflicting.put(position, Observation.present(solid()));
            }
        }
        SegmentedSpatialSnapshot spatial = snapshot(logical,
                segment(0, 25L, left, allAir(left)),
                segment(1, 26L, right, Map.copyOf(conflicting)));

        PathSearchResult result = new FlyPathSearch().search(request(
                spatial,
                new BoxSnapshot(1, 2, 2, 1.6D, 3.8D, 2.6D),
                new NavigationGoal(5.3D, 2.9D, 2.3D)));

        assertFalse(result.outcome() == PathSearchOutcome.FOUND);
        assertTrue(result.rejectedEvidence().contains(SpaceEvidenceReason.CONFLICT));
    }

    @Test
    void crossesOverlappedSegmentSeamButRejectsAnUncontainedBoundaryQuery() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 8, 6, 6);
        BoxSnapshot left = new BoxSnapshot(-2, -2, -2, 5, 8, 8);
        BoxSnapshot right = new BoxSnapshot(3, -2, -2, 10, 8, 8);
        SegmentedSpatialSnapshot overlapped = snapshot(logical,
                segment(0, 30L, left, allAir(left)),
                segment(1, 31L, right, allAir(right)));
        PathSearchResult success = new FlyPathSearch().search(request(
                overlapped,
                new BoxSnapshot(1, 2, 2, 1.6D, 3.8D, 2.6D),
                new NavigationGoal(7.3D, 2.9D, 2.3D)));
        assertEquals(PathSearchOutcome.FOUND, success.outcome());
        assertTrue(success.path().stream().anyMatch(node -> node.x() >= 4));

        BoxSnapshot abuttingLeft = new BoxSnapshot(-2, -2, -2, 4, 8, 8);
        BoxSnapshot abuttingRight = new BoxSnapshot(4, -2, -2, 10, 8, 8);
        SegmentedSpatialSnapshot abutting = snapshot(logical,
                segment(0, 32L, abuttingLeft, allAir(abuttingLeft)),
                segment(1, 33L, abuttingRight, allAir(abuttingRight)));
        PathSearchResult boundary = new FlyPathSearch().search(request(
                abutting,
                new BoxSnapshot(1, 2, 2, 1.6D, 3.8D, 2.6D),
                new NavigationGoal(7.3D, 2.9D, 2.3D)));
        assertFalse(boundary.outcome() == PathSearchOutcome.FOUND);
        assertTrue(boundary.rejectedEvidence().contains(SpaceEvidenceReason.SEGMENT_GAP));
    }

    @Test
    void enforcesExactFifteenHundredCeilingAndOverCeilingFailure() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 1_500, 4, 3);
        List<SpatialSegment> segments = new ArrayList<>();
        double[] starts = { -1, 247, 495, 743, 991, 1_239, 1_487 };
        for (int index = 0; index < starts.length; index++) {
            double maximum = Math.min(1_501, starts[index] + 256);
            BoxSnapshot bounds = new BoxSnapshot(starts[index], -1, 0, maximum, 4, 3);
            segments.add(segment(index, 40L + index, bounds, allAir(bounds)));
        }
        SegmentedSpatialSnapshot maximum = new SegmentedSpatialSnapshot(
                CAPTURE, logical, segments);
        BoxSnapshot body = new BoxSnapshot(1, 1, 1, 1.6D, 2.8D, 1.6D);
        PathSearchResult exact = new FlyPathSearch().search(request(
                maximum, body, new NavigationGoal(1_498.3D, 1.9D, 1.3D)));
        assertEquals(PathSearchOutcome.FOUND, exact.outcome());
        assertEquals(FlyPathSearch.MAX_DISTANCE, exact.maxDistance());

        PathSearchResult over = new FlyPathSearch().search(request(
                maximum, body, new NavigationGoal(1_503.3D, 1.9D, 1.3D)));
        assertEquals(PathSearchOutcome.NO_PATH, over.outcome());
        assertEquals(FlyPathSearch.MAX_DISTANCE, over.maxDistance());
        assertEquals(1, over.expandedNodes());
    }

    @Test
    void timesOutAtExactlyTenThousandMilliseconds() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 8, 6, 6);
        BoxSnapshot capture = expanded(logical);
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> calls.getAndIncrement() == 0 ? 50L : 10_050L;
        PathSearchResult result = new FlyPathSearch(clock, 100).search(request(
                snapshot(logical, segment(0, 50L, capture, allAir(capture))),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(6.3D, 2.9D, 2.3D)));

        assertEquals(PathSearchOutcome.TIMEOUT, result.outcome());
        assertEquals(FlyPathSearch.TIMEOUT_MILLIS, result.elapsedMillis());
        assertEquals(0, result.expandedNodes());
    }

    @Test
    void stopsAtHardExpansionBudgetDeterministically() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 10, 8, 8);
        BoxSnapshot capture = expanded(logical);
        PathSearchRequest request = request(
                snapshot(logical, segment(0, 60L, capture, allAir(capture))),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(8.3D, 2.9D, 2.3D));
        PathSearchResult result = new FlyPathSearch(() -> 0L, 2).search(request);

        assertEquals(PathSearchOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertEquals(2, result.expandedNodes());
        assertEquals(2, result.expansionBudget());
        assertTrue(result.path().isEmpty());
    }

    @Test
    void rejectsStaleTicketsAndInvalidGeometry() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 8, 6, 6);
        BoxSnapshot capture = expanded(logical);
        SegmentedSpatialSnapshot spatial =
                snapshot(logical, segment(0, 70L, capture, allAir(capture)));
        NavigationTicket staleRun =
                new NavigationTicket(new ControlOwner("search-test"), 8L, EPOCH);
        NavigationWorkTicket staleSearch =
                new NavigationWorkTicket(staleRun, NavigationPhase.SEARCHING, 3L);
        NavigationWorkTicket staleRevision =
                new NavigationWorkTicket(RUN, NavigationPhase.SEARCHING, 2L);
        NavigationWorkTicket repeatedSearch =
                new NavigationWorkTicket(RUN, NavigationPhase.SEARCHING, 4L);

        assertThrows(IllegalArgumentException.class, () -> new PathSearchRequest(
                staleSearch, spatial,
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4, 2, 2), NavigationMode.FLY));
        assertThrows(IllegalArgumentException.class, () -> new PathSearchRequest(
                staleRevision, spatial,
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4, 2, 2), NavigationMode.FLY));
        assertThrows(IllegalArgumentException.class, () -> new PathSearchRequest(
                CAPTURE, spatial,
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4, 2, 2), NavigationMode.FLY));
        assertThrows(IllegalArgumentException.class, () -> new PathSearchRequest(
                SEARCH, spatial,
                new BoxSnapshot(9, 2, 2, 9.6D, 3.8D, 2.6D),
                new NavigationGoal(4, 2, 2), NavigationMode.FLY));
        assertEquals(repeatedSearch, new PathSearchRequest(
                repeatedSearch, spatial,
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4, 2, 2), NavigationMode.FLY).workTicket());
    }

    private static void assertBlockedBy(
            Observation<BlockStateSnapshot> obstruction,
            SpaceEvidenceReason reason
    ) {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 6, 6, 6);
        BoxSnapshot capture = expanded(logical);
        Map<BlockPosition, Observation<BlockStateSnapshot>> cells = new LinkedHashMap<>();
        for (BlockPosition position : allAir(capture).keySet()) {
            cells.put(position, obstruction);
        }
        PathSearchResult result = new FlyPathSearch().search(request(
                snapshot(logical, segment(0, 20L + reason.ordinal(), capture, Map.copyOf(cells))),
                new BoxSnapshot(2, 2, 2, 2.6D, 3.8D, 2.6D),
                new NavigationGoal(4.3D, 2.9D, 2.3D)));
        assertFalse(result.outcome() == PathSearchOutcome.FOUND);
        assertTrue(result.rejectedEvidence().contains(reason));
    }

    private static PathSearchRequest request(
            SegmentedSpatialSnapshot snapshot,
            BoxSnapshot body,
            NavigationGoal goal
    ) {
        return new PathSearchRequest(SEARCH, snapshot, body, goal, NavigationMode.FLY);
    }

    private static SegmentedSpatialSnapshot snapshot(
            BoxSnapshot logical,
            SpatialSegment... segments
    ) {
        return new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(segments));
    }

    private static BoxSnapshot expanded(BoxSnapshot logical) {
        return new BoxSnapshot(
                logical.minX() - 2, logical.minY() - 2, logical.minZ() - 2,
                logical.maxX() + 2, logical.maxY() + 2, logical.maxZ() + 2);
    }

    private static SpatialSegment segment(
            int index,
            long token,
            BoxSnapshot bounds,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells
    ) {
        return new SpatialSegment(index, CAPTURE, token, raw(token, bounds, cells, true));
    }

    private static SpatialSnapshot raw(
            long token,
            BoxSnapshot bounds,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells,
            boolean loaded
    ) {
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped =
                new LinkedHashMap<>();
        cells.forEach((position, observation) -> grouped
                .computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                .put(position, observation));
        Map<ChunkPosition, ChunkSnapshot> chunks = new LinkedHashMap<>();
        grouped.forEach((position, blocks) ->
                chunks.put(position, new ChunkSnapshot(position, loaded, blocks)));
        return new SpatialSnapshot(
                EPOCH, token, bounds,
                (int) Math.floor(bounds.minY()), (int) Math.ceil(bounds.maxY()),
                new BoxSnapshot(
                        Math.max(bounds.minX(), 0), Math.max(bounds.minY(), 0),
                        Math.max(bounds.minZ(), 0),
                        Math.min(bounds.maxX(), Math.max(bounds.minX(), 0) + 0.6D),
                        Math.min(bounds.maxY(), Math.max(bounds.minY(), 0) + 1.8D),
                        Math.min(bounds.maxZ(), Math.max(bounds.minZ(), 0) + 0.6D)),
                chunks);
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> allAir(
            BoxSnapshot bounds
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> cells = new LinkedHashMap<>();
        for (int x = (int) Math.floor(bounds.minX()); x < (int) Math.ceil(bounds.maxX()); x++) {
            for (int y = (int) Math.floor(bounds.minY()); y < (int) Math.ceil(bounds.maxY()); y++) {
                for (int z = (int) Math.floor(bounds.minZ()); z < (int) Math.ceil(bounds.maxZ()); z++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    if (bounds.intersects(position.unitBox())) {
                        cells.put(position, Observation.present(air()));
                    }
                }
            }
        }
        return Map.copyOf(cells);
    }

    private static BlockStateSnapshot air() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(), EMPTY_FLUID,
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot solid() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:stone"), Map.of(), EMPTY_FLUID,
                Observation.present(new CollisionShapeSnapshot(
                        List.of(new BoxSnapshot(0, 0, 0, 1, 1, 1)))));
    }

    private static BlockStateSnapshot water() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:water"), Map.of(),
                ResourceIdentifier.parse("minecraft:water"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot collisionError() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(), EMPTY_FLUID,
                Observation.unknown());
    }
}
