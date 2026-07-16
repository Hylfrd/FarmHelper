package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure navigation-specific conservative clearance and WALK support policy. */
public final class Traversability {
    private static final double SUPPORT_PROBE_DEPTH = 1.0D / 1_024.0D;
    private static final ResourceIdentifier EMPTY_FLUID = ResourceIdentifier.parse("minecraft:empty");

    private Traversability() {
    }

    public static SpaceEvidence evaluate(
            SegmentedSpatialSnapshot spatial,
            NavigationWorkTicket expectedCapture,
            BoxSnapshot body,
            NavigationMode mode
    ) {
        Objects.requireNonNull(spatial, "spatial");
        Objects.requireNonNull(expectedCapture, "expectedCapture");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(mode, "mode");
        if (!spatial.workTicket().equals(expectedCapture)) {
            return unknown(SpaceEvidenceReason.STALE_TICKET, List.of());
        }
        if (!body.hasPositiveVolume()) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of());
        }

        BoxSnapshot support = new BoxSnapshot(
                body.minX(), body.minY() - SUPPORT_PROBE_DEPTH, body.minZ(),
                body.maxX(), body.minY(), body.maxZ());
        BlockGrid bodyGrid = collisionOrigins(body);
        BlockGrid supportGrid = mode == NavigationMode.WALK ? collisionOrigins(support) : null;
        if (bodyGrid == null || mode == NavigationMode.WALK && supportGrid == null) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of());
        }
        BoxSnapshot requiredEnvelope = mode == NavigationMode.WALK
                ? bodyGrid.captureBounds().span(supportGrid.captureBounds())
                : bodyGrid.captureBounds();
        // Logical bounds describe the requested body region. Collision origins may live in a
        // bounded segment halo, so only the body itself must remain inside the logical request.
        if (!spatial.bounds().contains(body)) {
            return unknown(SpaceEvidenceReason.OUTSIDE_BOUNDS, List.of());
        }
        BlockGrid requiredGrid = grid(requiredEnvelope);
        if (requiredGrid == null) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of());
        }

        List<SpatialSegment> candidates = spatial.containing(requiredEnvelope);
        if (candidates.isEmpty()) {
            return unknown(SpaceEvidenceReason.SEGMENT_GAP, List.of());
        }
        List<Integer> candidateIndexes = candidates.stream().map(SpatialSegment::index).toList();
        if (conflictingEvidence(
                spatial.intersecting(requiredEnvelope), requiredGrid, expectedCapture)) {
            return unknown(SpaceEvidenceReason.CONFLICT, candidateIndexes);
        }

        SpaceEvidence strongest = null;
        for (SpatialSegment segment : candidates) {
            SpaceEvidence evaluated = evaluateSegment(
                    segment, expectedCapture, body, support, bodyGrid, supportGrid, mode);
            if (evaluated.traversable()) {
                return evaluated;
            }
            if (strongest == null || rank(evaluated.reason()) < rank(strongest.reason())) {
                strongest = evaluated;
            }
        }
        return Objects.requireNonNull(strongest, "strongest");
    }

    private static SpaceEvidence evaluateSegment(
            SpatialSegment segment,
            NavigationWorkTicket workTicket,
            BoxSnapshot body,
            BoxSnapshot support,
            BlockGrid bodyGrid,
            BlockGrid supportGrid,
            NavigationMode mode
    ) {
        SpatialSnapshot snapshot = segment.snapshot();
        for (BlockPosition position : positions(bodyGrid)) {
            BlockEvidence evidence = block(snapshot, workTicket, position);
            if (evidence.reason() != SpaceEvidenceReason.PASSABLE) {
                return from(evidence.reason(), segment.index());
            }
            if (position.unitBox().intersects(body)
                    && !EMPTY_FLUID.equals(evidence.state().fluidId())) {
                return blocked(SpaceEvidenceReason.FLUID_OBSTRUCTION, segment.index());
            }
            if (evidence.state().collision().get().clearance(position, body) == SpaceStatus.BLOCKED) {
                return blocked(SpaceEvidenceReason.COLLISION, segment.index());
            }
        }
        if (mode == NavigationMode.FLY) {
            return passable(segment.index());
        }

        boolean supported = false;
        for (BlockPosition position : positions(supportGrid)) {
            BlockEvidence evidence = block(snapshot, workTicket, position);
            if (evidence.reason() != SpaceEvidenceReason.PASSABLE) {
                return from(evidence.reason(), segment.index());
            }
            if (position.unitBox().intersects(support)
                    && !EMPTY_FLUID.equals(evidence.state().fluidId())) {
                return blocked(SpaceEvidenceReason.FLUID_OBSTRUCTION, segment.index());
            }
            supported |= evidence.state().collision().get().support(position, support)
                    == SpaceStatus.PASSABLE;
        }
        return supported ? passable(segment.index())
                : blocked(SpaceEvidenceReason.NO_SUPPORT, segment.index());
    }

    private static BlockEvidence block(
            SpatialSnapshot snapshot,
            NavigationWorkTicket workTicket,
            BlockPosition position
    ) {
        if (snapshot.worldEpoch() != workTicket.worldEpoch()
                || position.y() < snapshot.minY() || position.y() >= snapshot.maxY()) {
            return new BlockEvidence(null, SpaceEvidenceReason.UNKNOWN_EVIDENCE);
        }
        ChunkSnapshot chunk = snapshot.chunks().get(position.chunk());
        if (chunk == null) {
            return new BlockEvidence(null, SpaceEvidenceReason.MISSING_EVIDENCE);
        }
        if (!chunk.loaded()) {
            return new BlockEvidence(null, SpaceEvidenceReason.UNLOADED);
        }
        Observation<BlockStateSnapshot> observed = chunk.block(position);
        if (observed.isAbsent()) {
            return new BlockEvidence(null, SpaceEvidenceReason.MISSING_EVIDENCE);
        }
        if (!observed.isPresent()) {
            return new BlockEvidence(null, SpaceEvidenceReason.UNKNOWN_EVIDENCE);
        }
        BlockStateSnapshot state = observed.get();
        if (!state.collision().isPresent()) {
            return new BlockEvidence(state, SpaceEvidenceReason.COLLISION_ERROR);
        }
        return new BlockEvidence(state, SpaceEvidenceReason.PASSABLE);
    }

    private static boolean conflictingEvidence(
            List<SpatialSegment> segments,
            BlockGrid grid,
            NavigationWorkTicket workTicket
    ) {
        for (BlockPosition position : positions(grid)) {
            BlockStateSnapshot known = null;
            for (SpatialSegment segment : segments) {
                if (!segment.snapshot().bounds().intersects(position.unitBox())) {
                    continue;
                }
                Observation<BlockStateSnapshot> observed = segment.snapshot().block(
                        workTicket.worldEpoch(), position);
                if (!observed.isPresent()) {
                    continue;
                }
                if (known != null && !known.equals(observed.get())) {
                    return true;
                }
                known = observed.get();
            }
        }
        return false;
    }

    private static BlockGrid collisionOrigins(BoxSnapshot box) {
        if (!box.hasPositiveVolume()) {
            return null;
        }
        try {
            long minX = Math.addExact(
                    (long) checkedFloor(
                            box.minX()
                                    - CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE), 1L);
            long minY = Math.addExact(
                    (long) checkedFloor(
                            box.minY()
                                    - CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE), 1L);
            long minZ = Math.addExact(
                    (long) checkedFloor(
                            box.minZ()
                                    - CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE), 1L);
            long maxX = checkedCeil(
                    box.maxX() - CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE);
            long maxY = checkedCeil(
                    box.maxY() - CollisionShapeSnapshot.MIN_VERTICAL_LOCAL_COORDINATE);
            long maxZ = checkedCeil(
                    box.maxZ() - CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE);
            return boundedGrid(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static BlockGrid grid(BoxSnapshot box) {
        try {
            int minX = checkedFloor(box.minX());
            int minY = checkedFloor(box.minY());
            int minZ = checkedFloor(box.minZ());
            long maxX = checkedCeil(box.maxX());
            long maxY = checkedCeil(box.maxY());
            long maxZ = checkedCeil(box.maxZ());
            return boundedGrid(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static BlockGrid boundedGrid(
            long minX,
            long minY,
            long minZ,
            long maxX,
            long maxY,
            long maxZ
    ) {
        long x = Math.subtractExact(maxX, minX);
        long y = Math.subtractExact(maxY, minY);
        long z = Math.subtractExact(maxZ, minZ);
        long cells = Math.multiplyExact(Math.multiplyExact(x, y), z);
        if (minX < Integer.MIN_VALUE || minY < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
                || maxX > (long) Integer.MAX_VALUE + 1L
                || maxY > (long) Integer.MAX_VALUE + 1L
                || maxZ > (long) Integer.MAX_VALUE + 1L
                || x <= 0L || y <= 0L || z <= 0L
                || cells <= 0L || cells > SpatialCaptureRequest.MAX_BLOCKS) {
            return null;
        }
        return new BlockGrid((int) minX, (int) minY, (int) minZ, maxX, maxY, maxZ);
    }

    private static List<BlockPosition> positions(BlockGrid grid) {
        List<BlockPosition> positions = new ArrayList<>();
        for (long x = grid.minX(); x < grid.maxX(); x++) {
            for (long y = grid.minY(); y < grid.maxY(); y++) {
                for (long z = grid.minZ(); z < grid.maxZ(); z++) {
                    positions.add(new BlockPosition((int) x, (int) y, (int) z));
                }
            }
        }
        return positions;
    }

    private static int checkedFloor(double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new ArithmeticException("coordinate outside integer block range");
        }
        return (int) floor;
    }

    private static long checkedCeil(double value) {
        double ceil = Math.ceil(value);
        if (ceil < Integer.MIN_VALUE || ceil > (double) Integer.MAX_VALUE + 1.0D) {
            throw new ArithmeticException("coordinate outside integer block range");
        }
        return (long) ceil;
    }

    private static SpaceEvidence passable(int segment) {
        return new SpaceEvidence(SpaceStatus.PASSABLE, SpaceEvidenceReason.PASSABLE, List.of(segment));
    }

    private static SpaceEvidence blocked(SpaceEvidenceReason reason, int segment) {
        return new SpaceEvidence(SpaceStatus.BLOCKED, reason, List.of(segment));
    }

    private static SpaceEvidence unknown(SpaceEvidenceReason reason, List<Integer> segments) {
        return new SpaceEvidence(SpaceStatus.UNKNOWN, reason, segments);
    }

    private static SpaceEvidence from(SpaceEvidenceReason reason, int segment) {
        return switch (reason) {
            case COLLISION, FLUID_OBSTRUCTION, NO_SUPPORT -> blocked(reason, segment);
            case PASSABLE -> passable(segment);
            default -> unknown(reason, List.of(segment));
        };
    }

    private static int rank(SpaceEvidenceReason reason) {
        return switch (reason) {
            case COLLISION -> 0;
            case FLUID_OBSTRUCTION -> 1;
            case COLLISION_ERROR -> 2;
            case UNLOADED -> 3;
            case MISSING_EVIDENCE -> 4;
            case UNKNOWN_EVIDENCE -> 5;
            case NO_SUPPORT -> 6;
            default -> 7;
        };
    }

    private record BlockEvidence(BlockStateSnapshot state, SpaceEvidenceReason reason) {
    }

    private record BlockGrid(
            int minX,
            int minY,
            int minZ,
            long maxX,
            long maxY,
            long maxZ
    ) {
        private BoxSnapshot captureBounds() {
            return new BoxSnapshot(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
