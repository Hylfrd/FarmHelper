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
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
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
    private static final NavigationWorkTicket CAPTURE = new NavigationWorkTicket(
            TICKET, NavigationPhase.CAPTURING, 2L);
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

        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(CAPTURE, logical, segments);

        assertEquals(7, snapshot.segments().size());
        assertEquals(7, snapshot.requestTokens().size());
        assertEquals(0, snapshot.capturedCellCount());
        assertEquals(256.0D, snapshot.segments().getFirst().snapshot().bounds().width());
        assertTrue(7L * dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest.MAX_BLOCKS
                <= SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.segments().add(snapshot.segments().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE,
                new BoxSnapshot(0.0D, 0.0D, 0.0D, 1_501.0D, 4.0D, 4.0D),
                segments));
    }

    @Test
    void listIsCopiedAndSegmentIdentityOrderingAndTokensAreExact() {
        BoxSnapshot bounds = new BoxSnapshot(0.0D, 0.0D, 0.0D, 4.0D, 4.0D, 4.0D);
        List<SpatialSegment> mutable = new ArrayList<>(List.of(segment(0, 1L, bounds, Map.of())));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(CAPTURE, bounds, mutable);
        mutable.clear();
        assertEquals(1, snapshot.segments().size());

        SpatialSnapshot untagged = raw(0L, EPOCH, bounds, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, CAPTURE, 0L, untagged));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(-1, CAPTURE, 1L, raw(1L, EPOCH, bounds, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, CAPTURE, 2L, raw(1L, EPOCH, bounds, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialSegment(0, CAPTURE, 1L, raw(1L, EPOCH + 1L, bounds, Map.of())));

        SpatialSegment only = segment(0, 1L, bounds, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                new NavigationWorkTicket(
                        new NavigationTicket(TICKET.owner(), TICKET.generation() + 1L, EPOCH),
                        NavigationPhase.CAPTURING, CAPTURE.revision()),
                bounds, List.of(only)));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(only, new SpatialSegment(
                        1, CAPTURE, 1L, raw(1L, EPOCH, bounds.move(3.0D, 0.0D, 0.0D), Map.of())))));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(new SpatialSegment(
                        1, CAPTURE, 2L, raw(2L, EPOCH, bounds, Map.of())))));

        List<SpatialSegment> tooMany = new ArrayList<>();
        for (int index = 0; index <= SegmentedSpatialSnapshot.MAX_SEGMENTS; index++) {
            tooMany.add(only);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedSpatialSnapshot(CAPTURE, bounds, tooMany));

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
                () -> new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(leftGap, rightGap)));

        SpatialSegment outer = segment(0, 3L, logical, Map.of());
        SpatialSegment nested = segment(1, 4L,
                new BoxSnapshot(2.0D, 0.5D, 0.5D, 8.0D, 2.5D, 1.5D), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(outer, nested)));

        BoxSnapshot extended = new BoxSnapshot(-17.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D);
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE, logical, List.of(segment(0, 5L, extended, Map.of()))));
    }

    @Test
    void seamRequiresOneWholeSegmentAndKnownOverlapMaySatisfyIt() {
        BoxSnapshot logical = new BoxSnapshot(0.0D, 0.0D, 0.0D, 10.0D, 3.0D, 2.0D);
        SegmentedSpatialSnapshot abutting = new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(
                segment(0, 1L, new BoxSnapshot(0, 0, -1, 5, 3, 2), Map.of()),
                segment(1, 2L, new BoxSnapshot(5, 0, -1, 10, 3, 2), Map.of())));
        BoxSnapshot crossing = new BoxSnapshot(4.8D, 1.1D, 0.2D, 5.2D, 1.8D, 0.8D);
        SpaceEvidence gap = Traversability.evaluate(
                abutting, CAPTURE, crossing, NavigationMode.FLY);
        assertFalse(gap.traversable());
        assertEquals(SpaceEvidenceReason.SEGMENT_GAP, gap.reason());

        BoxSnapshot leftBounds = new BoxSnapshot(0, 0, -1, 6, 3, 2);
        BoxSnapshot rightBounds = new BoxSnapshot(4, 0, -1, 10, 3, 2);
        SegmentedSpatialSnapshot halo = new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(
                segment(0, 3L, leftBounds, allAir(leftBounds)),
                segment(1, 4L, rightBounds, allAir(rightBounds))));
        assertTrue(Traversability.evaluate(halo, CAPTURE, crossing, NavigationMode.FLY)
                .traversable());
    }

    @Test
    void conflictingOverlapStaleTicketAndOutsideBoundsRetainDistinctReasons() {
        BoxSnapshot logical = new BoxSnapshot(0, 0, 0, 10, 3, 2);
        BlockPosition overlap = new BlockPosition(4, 1, 0);
        BoxSnapshot leftBounds = new BoxSnapshot(0, 0, -1, 6, 3, 2);
        BoxSnapshot rightBounds = new BoxSnapshot(4, 0, -1, 10, 3, 2);
        SegmentedSpatialSnapshot conflict = new SegmentedSpatialSnapshot(CAPTURE, logical, List.of(
                segment(0, 1L, leftBounds,
                        withCell(allAir(leftBounds), overlap, Observation.present(air()))),
                segment(1, 2L, rightBounds,
                        withCell(allAir(rightBounds), overlap, Observation.present(solid())))));
        BoxSnapshot query = new BoxSnapshot(4.2D, 1.1D, 0.2D, 4.8D, 1.8D, 0.8D);

        assertEquals(SpaceEvidenceReason.CONFLICT,
                Traversability.evaluate(conflict, CAPTURE, query, NavigationMode.FLY).reason());
        assertEquals(SpaceEvidenceReason.STALE_TICKET,
                Traversability.evaluate(conflict,
                        new NavigationWorkTicket(
                                new NavigationTicket(TICKET.owner(), TICKET.generation() + 1L, EPOCH),
                                NavigationPhase.CAPTURING, CAPTURE.revision()),
                        query, NavigationMode.FLY).reason());
        assertEquals(SpaceEvidenceReason.OUTSIDE_BOUNDS,
                Traversability.evaluate(conflict, CAPTURE,
                        query.move(20.0D, 0.0D, 0.0D), NavigationMode.FLY).reason());
    }

    @Test
    void onlyFullyKnownEmptyFluidAndCollisionEvidenceIsFlyTraversable() {
        BoxSnapshot bounds = new BoxSnapshot(-1, 0, -1, 2, 3, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.1D, 0.2D, 0.8D, 1.8D, 0.8D);
        BlockPosition block = new BlockPosition(0, 1, 0);
        BlockPosition first = new BlockPosition(-1, 0, -1);

        assertEvidence(bounds, body, Map.of(), SpaceEvidenceReason.MISSING_EVIDENCE);
        assertEvidence(bounds, body, withCell(allAir(bounds), first, Observation.unknown()),
                SpaceEvidenceReason.UNKNOWN_EVIDENCE);
        assertEvidence(bounds, body, withCell(allAir(bounds), first, Observation.absent()),
                SpaceEvidenceReason.MISSING_EVIDENCE);
        assertEvidence(bounds, body,
                withCell(allAir(bounds), block, Observation.present(collisionError())),
                SpaceEvidenceReason.COLLISION_ERROR);
        assertEvidence(bounds, body,
                withCell(allAir(bounds), block, Observation.present(water())),
                SpaceEvidenceReason.FLUID_OBSTRUCTION);
        assertEvidence(bounds, body,
                withCell(allAir(bounds), block, Observation.present(solid())),
                SpaceEvidenceReason.COLLISION);

        Map<ChunkPosition, ChunkSnapshot> unloadedChunks = new LinkedHashMap<>();
        for (int chunkX = -1; chunkX <= 0; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 0; chunkZ++) {
                ChunkPosition position = new ChunkPosition(chunkX, chunkZ);
                unloadedChunks.put(position, new ChunkSnapshot(position, false, Map.of()));
            }
        }
        SpatialSnapshot unloaded = new SpatialSnapshot(
                EPOCH, 20L, bounds, 0, 3,
                new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D),
                unloadedChunks);
        SegmentedSpatialSnapshot unloadedSegment = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(new SpatialSegment(0, CAPTURE, 20L, unloaded)));
        assertEquals(SpaceEvidenceReason.UNLOADED,
                Traversability.evaluate(unloadedSegment, CAPTURE, body, NavigationMode.FLY).reason());

        SegmentedSpatialSnapshot passable = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 21L, bounds,
                allAir(bounds))));
        assertTrue(Traversability.evaluate(passable, CAPTURE, body, NavigationMode.FLY)
                .traversable());
    }

    @Test
    void walkNeedsFullyKnownClearanceAndKnownSolidSupport() {
        BoxSnapshot bounds = new BoxSnapshot(-1, 0, -1, 2, 4, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D);
        Map<BlockPosition, Observation<BlockStateSnapshot>> supported = withCell(
                allAir(bounds), new BlockPosition(0, 0, 0), Observation.present(solid()));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 1L, bounds, supported)));
        assertTrue(Traversability.evaluate(snapshot, CAPTURE, body, NavigationMode.WALK)
                .traversable());

        Map<BlockPosition, Observation<BlockStateSnapshot>> noSupport = allAir(bounds);
        SegmentedSpatialSnapshot unsupported = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 2L, bounds, noSupport)));
        assertEquals(SpaceEvidenceReason.NO_SUPPORT,
                Traversability.evaluate(unsupported, CAPTURE, body, NavigationMode.WALK).reason());
    }

    @Test
    void adjacentLegalCollisionProtrusionsAreCheckedOnEveryFace() {
        BoxSnapshot bounds = new BoxSnapshot(-1, 0, -1, 2, 3, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 1.8D, 0.8D);
        Map<BlockPosition, Observation<BlockStateSnapshot>> air = allAir(bounds);

        assertCollision(bounds, body, air, new BlockPosition(0, 0, 0),
                new BoxSnapshot(0, 0, 0, 1, 1.5D, 1), NavigationMode.FLY);
        assertCollision(bounds, body, air, new BlockPosition(1, 1, 0),
                new BoxSnapshot(-0.5D, 0, 0, 1, 1, 1), NavigationMode.FLY);
        assertCollision(bounds, body, air, new BlockPosition(0, 2, 0),
                new BoxSnapshot(0, -0.5D, 0, 1, 1, 1), NavigationMode.FLY);

        Map<BlockPosition, Observation<BlockStateSnapshot>> boundaryTouch = withCell(
                air, new BlockPosition(0, 0, 0),
                Observation.present(shaped(new BoxSnapshot(0, 0, 0, 1, 1, 1))));
        SegmentedSpatialSnapshot touching = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 40L, bounds, boundaryTouch)));
        assertTrue(Traversability.evaluate(touching, CAPTURE, body, NavigationMode.FLY)
                .traversable());

        Map<BlockPosition, Observation<BlockStateSnapshot>> unknownNeighbor =
                new LinkedHashMap<>(air);
        unknownNeighbor.remove(new BlockPosition(-1, 0, -1));
        SegmentedSpatialSnapshot unknown = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 41L, bounds, unknownNeighbor)));
        assertEquals(SpaceEvidenceReason.UNKNOWN_EVIDENCE,
                Traversability.evaluate(unknown, CAPTURE, body, NavigationMode.FLY).reason());

        Map<BlockPosition, Observation<BlockStateSnapshot>> invalidShape = withCell(
                air, new BlockPosition(0, 1, 0), Observation.present(shaped(
                        new BoxSnapshot(0, 0, 0, 1.5001D, 1, 1))));
        SegmentedSpatialSnapshot invalid = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 42L, bounds, invalidShape)));
        assertEquals(SpaceEvidenceReason.COLLISION_ERROR,
                Traversability.evaluate(invalid, CAPTURE, body, NavigationMode.FLY).reason());
    }

    @Test
    void protrudingWalkSupportIsRejectedAsBodyCollision() {
        BoxSnapshot bounds = new BoxSnapshot(-1, 0, -1, 2, 4, 2);
        BoxSnapshot body = new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D);
        Map<BlockPosition, Observation<BlockStateSnapshot>> fence = withCell(
                allAir(bounds), new BlockPosition(0, 0, 0), Observation.present(shaped(
                        new BoxSnapshot(0, 0, 0, 1, 1.5D, 1))));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 43L, bounds, fence)));

        assertEquals(SpaceEvidenceReason.COLLISION,
                Traversability.evaluate(snapshot, CAPTURE, body, NavigationMode.WALK).reason());
    }

    @Test
    void spaceStatusAndReasonCompatibilityIsExhaustive() {
        for (SpaceEvidenceReason reason : SpaceEvidenceReason.values()) {
            SpaceStatus compatible = switch (reason) {
                case PASSABLE -> SpaceStatus.PASSABLE;
                case COLLISION, FLUID_OBSTRUCTION, NO_SUPPORT -> SpaceStatus.BLOCKED;
                default -> SpaceStatus.UNKNOWN;
            };
            assertEquals(compatible,
                    new SpaceEvidence(compatible, reason, List.of()).status());
            for (SpaceStatus status : SpaceStatus.values()) {
                if (status != compatible) {
                    assertThrows(IllegalArgumentException.class,
                            () -> new SpaceEvidence(status, reason, List.of()));
                }
            }
        }
    }

    @Test
    void segmentCountLimitsAreExercisedThroughRealConstructors() {
        BoxSnapshot exactLogical = new BoxSnapshot(0, 0, 0, 256, 1, 1);
        List<SpatialSegment> exact = new ArrayList<>();
        for (int index = 0; index < SegmentedSpatialSnapshot.MAX_SEGMENTS; index++) {
            double start = index * 8.0D;
            exact.add(segment(index, 100L + index,
                    new BoxSnapshot(start, 0, 0, start + 9.0D, 1, 1), Map.of()));
        }
        assertEquals(SegmentedSpatialSnapshot.MAX_SEGMENTS,
                new SegmentedSpatialSnapshot(CAPTURE, exactLogical, exact).segments().size());

        List<SpatialSegment> over = new ArrayList<>(exact);
        over.add(segment(SegmentedSpatialSnapshot.MAX_SEGMENTS, 200L,
                new BoxSnapshot(256, 0, 0, 265, 1, 1), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE, new BoxSnapshot(0, 0, 0, 264, 1, 1), over));
    }

    @Test
    void aggregateCellLimitIsExercisedThroughRealSegments() {
        List<SpatialSegment> exact = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int start = index * 31;
            BoxSnapshot segmentBounds = new BoxSnapshot(start, 0, 0, start + 32, 16, 16);
            exact.add(segment(index, 300L + index, segmentBounds, allAir(segmentBounds)));
        }
        BoxSnapshot exactLogical = new BoxSnapshot(0, 0, 0, 249, 16, 16);
        SegmentedSpatialSnapshot maximum = new SegmentedSpatialSnapshot(
                CAPTURE, exactLogical, exact);
        assertEquals(SegmentedSpatialSnapshot.MAX_AGGREGATE_CELLS,
                maximum.capturedCellCount());

        List<SpatialSegment> over = new ArrayList<>(exact);
        BoxSnapshot ninthBounds = new BoxSnapshot(248, 0, 0, 280, 16, 16);
        over.add(segment(8, 400L, ninthBounds, Map.of(
                new BlockPosition(248, 0, 0), Observation.present(air()))));
        assertThrows(IllegalArgumentException.class, () -> new SegmentedSpatialSnapshot(
                CAPTURE, new BoxSnapshot(0, 0, 0, 280, 16, 16), over));
    }

    private static void assertEvidence(
            BoxSnapshot bounds,
            BoxSnapshot body,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells,
            SpaceEvidenceReason expected
    ) {
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 1L, bounds, cells)));
        SpaceEvidence evidence = Traversability.evaluate(snapshot, CAPTURE, body, NavigationMode.FLY);
        assertFalse(evidence.traversable());
        assertEquals(expected, evidence.reason());
    }

    private static void assertCollision(
            BoxSnapshot bounds,
            BoxSnapshot body,
            Map<BlockPosition, Observation<BlockStateSnapshot>> base,
            BlockPosition position,
            BoxSnapshot shape,
            NavigationMode mode
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> cells = withCell(
                base, position, Observation.present(shaped(shape)));
        SegmentedSpatialSnapshot snapshot = new SegmentedSpatialSnapshot(
                CAPTURE, bounds, List.of(segment(0, 50L, bounds, cells)));
        assertEquals(SpaceEvidenceReason.COLLISION,
                Traversability.evaluate(snapshot, CAPTURE, body, mode).reason());
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

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> withCell(
            Map<BlockPosition, Observation<BlockStateSnapshot>> base,
            BlockPosition position,
            Observation<BlockStateSnapshot> observation
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> cells = new LinkedHashMap<>(base);
        cells.put(position, observation);
        return Map.copyOf(cells);
    }

    private static SpatialSegment segment(
            int index,
            long token,
            BoxSnapshot bounds,
            Map<BlockPosition, Observation<BlockStateSnapshot>> cells
    ) {
        return new SpatialSegment(index, CAPTURE, token, raw(token, EPOCH, bounds, cells));
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

    private static BlockStateSnapshot shaped(BoxSnapshot shape) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:test_shape"), Map.of(), EMPTY,
                Observation.present(new CollisionShapeSnapshot(List.of(shape))));
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
