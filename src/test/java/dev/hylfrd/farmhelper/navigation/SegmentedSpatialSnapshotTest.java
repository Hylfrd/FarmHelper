package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedSpatialSnapshotTest {
    private static final long EPOCH = 12L;
    private static final NavigationTicket TICKET = new NavigationTicket(
            new ControlOwner("spatial-navigation"), 4L, EPOCH);
    private static final ResourceIdentifier EMPTY = ResourceIdentifier.parse("minecraft:empty");

    @Test
    void sevenUnchangedBoundedSegmentsSupportAFull1500BlockLogicalRequest() {
        BoxSnapshot logical = new BoxSnapshot(0.0D, 0.0D, 0.0D, 1_500.0D, 4.0D, 4.0D);
        List<SpatialSegment> segments = new ArrayList<>();
        double[] starts = {0.0D, 248.0D, 496.0D, 744.0D, 992.0D, 1_240.0D, 1_488.0D};
        for (int index = 0; index < starts.length; index++) {
            double maximum = Math.min(1_500.0D, starts[index] + 256.0D);
            BoxSnapshot bounds = new BoxSnapshot(
                    starts[index], 0.0D, 0.0D, maximum, 4.0D, 4.0D);
            segments.add(segment(index, index + 1L, bounds, Map.of()));
        }

        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(TICKET, logical, segments);

        assertEquals(7, snapshot.segments().size());
        assertEquals(7, snapshot.requestTokens().size());
        assertEquals(0, snapshot.capturedCellCount());
        assertEquals(256.0D, snapshot.segments().getFirst().snapshot().bounds().width());
        assertTrue(7L * dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest.MAX_BLOCKS
                <= SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.segments().add(snapshot.segments().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                TICKET,
                new BoxSnapshot(0.0D, 0.0D, 0.0D, 1_500.0001D, 4.0D, 4.0D),
                segments));
    }

    @Test
    void listIsCopiedAndSegmentIdentityOrderingAndTokensAreExact() {
        BoxSnapshot bounds = new BoxSnapshot(0.0D, 0.0D, 0.0D, 4.0D, 4.0D, 4.0D);
        List<SpatialSegment> mutable = new ArrayList<>(List.of(segment(0, 1L, bounds, Map.of())));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(TICKET, bounds, mutable);
        mutable.clear();
        assertEquals(1, snapshot.segments().size());

        SpatialSnapshot untagged = raw(0L, EPOCH, bounds, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, TICKET, 0L, untagged));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(-1, TICKET, 1L, raw(1L, EPOCH, bounds, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, TICKET, 2L, raw(1L, EPOCH, bounds, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, TICKET, 1L, raw(1L, EPOCH + 1L, bounds, Map.of())));

        SpatialSegment only = segment(0, 1L, bounds, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                new NavigationTicket(TICKET.owner(), TICKET.generation() + 1L, EPOCH),
                bounds, List.of(only)));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(only, new SpatialSegment(
                        1, TICKET, 1L, raw(1L, EPOCH, bounds.move(3.0D, 0.0D, 0.0D), Map.of())))));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(new SpatialSegment(
                        1, TICKET, 2L, raw(2L, EPOCH, bounds, Map.of())))));

        List<SpatialSegment> tooMany = new ArrayList<>();
        for (int index = 0; index <= SegmentedSpatialSnapshot.MAX_SEGMENTS; index++) {
            tooMany.add(only);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedSpatialSnapshot(TICKET, bounds, tooMany));

        SegmentedSpatialSnapshot.validateAggregateCellCount(
                SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS);
        assertThrows(IllegalArgumentException.class,
                () -> SegmentedSpatialSnapshot.validateAggregateCellCount(
                        (long) SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS + 1L));
        assertThrows(IllegalArgumentException.class,
                () -> SegmentedSpatialSnapshot.validateAggregateCellCount(-1L));
    }

    @Test
    void constructionRejectsCoverageGapsIllegalNestingAndOversizedHalo() {
        BoxSnapshot logical = new BoxSnapshot(0.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D);
        SpatialSegment leftGap = segment(0, 1L,
                new BoxSnapshot(0.0D, 0.0D, 0.0D, 5.0D, 3.0D, 2.0D), Map.of());
        SpatialSegment rightGap = segment(1, 2L,
                new BoxSnapshot(6.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedSpatialSnapshot(TICKET, logical, List.of(leftGap, rightGap)));

        SpatialSegment outer = segment(0, 3L, logical, Map.of());
        SpatialSegment nested = segment(1, 4L,
                new BoxSnapshot(2.0D, 0.5D, 0.5D, 8.0D, 2.5D, 1.5D), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedSpatialSnapshot(TICKET, logical, List.of(outer, nested)));

        BoxSnapshot extended = new BoxSnapshot(-17.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D);
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                TICKET, logical, List.of(segment(0, 5L, extended, Map.of()))));
    }

    @Test
    void seamRequiresOneWholeSegmentAndKnownOverlapMaySatisfyIt() {
        BoxSnapshot logical = new BoxSnapshot(0.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D);
        SegmentedSpatialSnapshot abutting = new SegmentedSpatialSnapshot(TICKET, logical, List.of(
                segment(0, 1L, new BoxSnapshot(0, 0, 0, 5, 3, 2), Map.of()),
                segment(1, 2L, new BoxSnapshot(5, 0, 0, 10, 3, 2), Map.of())));
        BoxSnapshot crossing = new BoxSnapshot(4.8D, 1.1D, 0.2D, 5.2D, 1.8D, 0.8D);
        SpaceEvidence gap = Traversability.evaluate(
                abutting, TICKET, crossing, NavigationMode.FLY);
        assertFalse(gap.traversable());
        assertEquals(SpaceEvidenceReason.SEGMENT_GAP, gap.reason());

        Map<BlockPosition, Observation<BlockStateSnapshot>> known = Map.of(
                new BlockPosition(4, 1, 0), Observation.present(air()),
                new BlockPosition(5, 1, 0), Observation.present(air()));
        SegmentedSpatialSnapshot halo = new SegmentedSpatialSnapshot(TICKET, logical, List.of(
                segment(0, 3L, new BoxSnapshot(0, 0, 0, 6, 3, 2), known),
                segment(1, 4L, new BoxSnapshot(4, 0, 0, 10, 3, 2), known)));
        assertTrue(Traversability.evaluate(halo, TICKET, crossing, NavigationMode.FLY)
                .traversable());
    }

    @Test
    void conflictingOverlapStaleTicketAndOutsideBoundsRetainDistinctReasons() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 10, 3, 2);
        BlockPosition overlap = new BlockPosition(4, 1, 0);
        SegmentedSpatialSnapshot conflict = new SegmentedSpatialSnapshot(TICKET, logical, List.of(
                segment(0, 1L, new BoxSnapshot(0, 0, 0, 6, 3, 2),
                        Map.of(overlap, Observation.present(air()))),
                segment(1, 2L, new BoxSnapshot(4, 0, 0, 10, 3, 2),
                        Map.of(overlap, Observation.present(solid())))));
        BoxSnapshot query = new BoxSnapshot(4.2D, 1.1D, 0.2D, 4.8D, 1.8D, 0.8D);

        assertEquals(SpaceEvidenceReason.CONFLICT,
                Traversability.evaluate(conflict, TICKET, query, NavigationMode.FLY).reason());
        assertEquals(SpaceEvidenceReason.STALE_TICKET,
                Traversability.evaluate(conflict,
                        new NavigationTicket(TICKET.owner(), TICKET.generation() + 1L, EPOCH),
                        query, NavigationMode.FLY).reason());
        assertEquals(SpaceEvidenceReason.OUTSIDE_BOUNDS,
                Traversability.evaluate(conflict, TICKET,
                        query.move(20.0D, 0.0D, 0.0D), NavigationMode.FLY).reason());
    }

    @Test
    void onlyFullyKnownEmptyFluidAndCollisionEvidenceIsFlyTraversable() {
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 2, 3, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.1D, 0.2D, 0.8D, 1.8D, 0.8D);
        BlockPosition block = new BlockPosition(0, 1, 0);

        assertEvidence(bounds, body, Map.of(), SpaceEvidenceReason.MISSING_EVIDENCE);
        assertEvidence(bounds, body, Map.of(block, Observation.unknown()),
                SpaceEvidenceReason.UNKNOWN_EVIDENCE);
        assertEvidence(bounds, body, Map.of(block, Observation.absent()),
                SpaceEvidenceReason.MISSING_EVIDENCE);
        assertEvidence(bounds, body, Map.of(block, Observation.present(collisionError())),
                SpaceEvidenceReason.COLLISION_ERROR);
        assertEvidence(bounds, body, Map.of(block, Observation.present(water())),
                SpaceEvidenceReason.FLUID_OBSTRUCTION);
        assertEvidence(bounds, body, Map.of(block, Observation.present(solid())),
                SpaceEvidenceReason.COLLISION);

        SpatialSnapshot unloaded = new SpatialSnapshot(
                EPOCH, 20L, bounds, 0, 3,
                new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D),
                Map.of(new ChunkPosition(0, 0),
                        new ChunkSnapshot(new ChunkPosition(0, 0), false, Map.of())));
        SegmentedSpatialSnapshot unloadedSegment = new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(new SpatialSegment(0, TICKET, 20L, unloaded)));
        assertEquals(SpaceEvidenceReason.UNLOADED,
                Traversability.evaluate(unloadedSegment, TICKET, body, NavigationMode.FLY).reason());

        SegmentedSpatialSnapshot passable = new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(segment(0, 21L, bounds,
                Map.of(block, Observation.present(air())))));
        assertTrue(Traversability.evaluate(passable, TICKET, body, NavigationMode.FLY)
                .traversable());
    }

    @Test
    void walkNeedsFullyKnownClearanceAndKnownSolidSupport() {
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 2, 4, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D);
        Map<BlockPosition, Observation<BlockStateSnapshot>> supported = Map.of(
                new BlockPosition(0, 0, 0), Observation.present(solid()),
                new BlockPosition(0, 1, 0), Observation.present(air()),
                new BlockPosition(0, 2, 0), Observation.present(air()));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(segment(0, 1L, bounds, supported)));
        assertTrue(Traversability.evaluate(snapshot, TICKET, body, NavigationMode.WALK)
                .traversable());

        Map<BlockPosition, Observation<BlockStateSnapshot>> noSupport = Map.of(
                new BlockPosition(0, 0, 0), Observation.present(air()),
                new BlockPosition(0, 1, 0), Observation.present(air()),
                new BlockPosition(0, 2, 0), Observation.present(air()));
        SegmentedSpatialSnapshot unsupported = new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(segment(0, 2L, bounds, noSupport)));
        assertEquals(SpaceEvidenceReason.NO_SUPPORT,
                Traversability.evaluate(unsupported, TICKET, body, NavigationMode.WALK).reason());
    }

    private static void assertEvidence(
            BoxSnapshot bounds,
            BoxSnapshot body,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells,
            SpaceEvidenceReason expected
    ) {
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                TICKET, bounds, List.of(segment(0, 1L, bounds, cells)));
        SpaceEvidence evidence = Traversability.evaluate(snapshot, TICKET, body, NavigationMode.FLY);
        assertFalse(evidence.traversable());
        assertEquals(expected, evidence.reason());
    }

    private static SpatialSegment segment(
            int index,
            long token,
            BoxSnapshot bounds,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells
    ) {
        return new SpatialSegment(index, TICKET, token, raw(token, EPOCH, bounds, cells));
    }

    private static SpatialSnapshot raw(
            long token,
            long epoch,
            BoxSnapshot bounds,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells
    ) {
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped =
                new LinkedHashMap<>();
        cells.forEach((position, observation) -> grouped
                .computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                .put(position, observation));
        Map<ChunkPosition, ChunkSnapshot> chunks = new LinkedHashMap<>();
        grouped.forEach((position, blocks) ->
                chunks.put(position, new ChunkSnapshot(position, true, blocks)));
        return new SpatialSnapshot(epoch, token, bounds,
                (int) Math.floor(bounds.minY()), (int) Math.ceil(bounds.maxY()),
                new BoxSnapshot(
                        bounds.minX(), bounds.minY(), bounds.minZ(),
                        Math.min(bounds.maxX(), bounds.minX() + 0.6D),
                        Math.min(bounds.maxY(), bounds.minY() + 1.8D),
                        Math.min(bounds.maxZ(), bounds.minZ() + 0.6D)),
                chunks);
    }

    private static BlockStateSnapshot air() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(), EMPTY,
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot solid() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:stone"), Map.of(), EMPTY,
                Observation.present(new CollisionShapeSnapshot(List.of(
                        new BoxSnapshot(0, 0, 0, 1, 1, 1)))));
    }

    private static BlockStateSnapshot collisionError() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(), EMPTY, Observation.unknown());
    }

    private static BlockStateSnapshot water() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:water"), Map.of(),
                ResourceIdentifier.parse("minecraft:water"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }
}
