package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
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
            NavigationTicket expectedTicket,
            BoxSnapshot body,
            NavigationMode mode
    ) {
        Objects.requireNonNull(spatial, "spatial");
        Objects.requireNonNull(expectedTicket, "expectedTicket");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(mode, "mode");
        if (!spatial.ticket().equals(expectedTicket)) {
            return unknown(SpaceEvidenceReason.STALE_TICKET, List.of());
        }
        if (!body.hasPositiveVolume()) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of());
        }

        BoxSnapshot support = new BoxSnapshot(
                body.minX(), body.minY() - SUPPORT_PROBE_DEPTH, body.minZ(),
                body.maxX(), body.minY(), body.maxZ());
        BoxSnapshot envelope = mode == NavigationMode.WALK ? body.span(support) : body;
        if (!spatial.bounds().contains(envelope)) {
            return unknown(SpaceEvidenceReason.OUTSIDE_BOUNDS, List.of());
        }
        BlockGrid envelopeGrid = grid(envelope);
        if (envelopeGrid == null) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of());
        }

        List<SpatialSegment> candidates = spatial.containing(envelope);
        if (candidates.isEmpty()) {
            return unknown(SpaceEvidenceReason.SEGMENT_GAP, List.of());
        }
        List<Integer> candidateIndexes = candidates.stream().map(SpatialSegment::index).toList();
        if (conflictingEvidence(spatial.intersecting(envelope), envelopeGrid, expectedTicket)) {
            return unknown(SpaceEvidenceReason.CONFLICT, candidateIndexes);
        }

        SpaceEvidence strongest = null;
        for (SpatialSegment segment : candidates) {
            SpaceEvidence evaluated = evaluateSegment(segment, expectedTicket, body, support, mode);
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
            NavigationTicket ticket,
            BoxSnapshot body,
            BoxSnapshot support,
            NavigationMode mode
    ) {
        SpatialSnapshot snapshot = segment.snapshot();
        BlockGrid bodyGrid = grid(body);
        if (bodyGrid == null) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of(segment.index()));
        }
        for (BlockPosition position : positions(bodyGrid)) {
            BlockEvidence evidence = block(snapshot, ticket, position);
            if (evidence.reason() != SpaceEvidenceReason.PASSABLE) {
                return from(evidence.reason(), segment.index());
            }
            if (!EMPTY_FLUID.equals(evidence.state().fluidId())) {
                return blocked(SpaceEvidenceReason.FLUID_OBSTRUCTION, segment.index());
            }
            if (evidence.state().collision().get().clearance(position, body) == SpaceStatus.BLOCKED) {
                return blocked(SpaceEvidenceReason.COLLISION, segment.index());
            }
        }
        if (mode == NavigationMode.FLY) {
            return passable(segment.index());
        }

        BlockGrid supportGrid = grid(support);
        if (supportGrid == null) {
            return unknown(SpaceEvidenceReason.QUERY_TOO_LARGE, List.of(segment.index()));
        }
        boolean supported = false;
        for (BlockPosition position : positions(supportGrid)) {
            BlockEvidence evidence = block(snapshot, ticket, position);
            if (evidence.reason() != SpaceEvidenceReason.PASSABLE) {
                return from(evidence.reason(), segment.index());
            }
            if (!EMPTY_FLUID.equals(evidence.state().fluidId())) {
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
            NavigationTicket ticket,
            BlockPosition position
    ) {
        if (snapshot.worldEpoch() != ticket.worldEpoch()
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
            NavigationTicket ticket
    ) {
        for (BlockPosition position : positions(grid)) {
            BlockStateSnapshot known = null;
            for (SpatialSegment segment : segments) {
                if (!segment.snapshot().bounds().intersects(position.unitBox())) {
                    continue;
                }
                Observation<BlockStateSnapshot> observed = segment.snapshot().block(
                        ticket.worldEpoch(), position);
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

    private static BlockGrid grid(BoxSnapshot box) {
        try {
            int minX = checkedFloor(box.minX());
            int minY = checkedFloor(box.minY());
            int minZ = checkedFloor(box.minZ());
            long maxX = checkedCeil(box.maxX());
            long maxY = checkedCeil(box.maxY());
            long maxZ = checkedCeil(box.maxZ());
            long x = Math.subtractExact(maxX, minX);
            long y = Math.subtractExact(maxY, minY);
            long z = Math.subtractExact(maxZ, minZ);
            long cells = Math.multiplyExact(Math.multiplyExact(x, y), z);
            if (x <= 0L || y <= 0L || z <= 0L
                    || cells <= 0L || cells > SpatialCaptureRequest.MAX_BLOCKS) {
                return null;
            }
            return new BlockGrid(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (ArithmeticException exception) {
            return null;
        }
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
    }
}
