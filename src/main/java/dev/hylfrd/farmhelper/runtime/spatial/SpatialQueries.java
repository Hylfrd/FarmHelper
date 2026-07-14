package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure conservative queries over a bounded {@link SpatialSnapshot}. */
public final class SpatialQueries {
    public static final int GARDEN_VOID_MIN_Y = 65;
    public static final int MAX_END_ROW_BLOCKS = 180;
    private static final double SUPPORT_PROBE_DEPTH = 1.0D / 1024.0D;
    private static final ResourceIdentifier EMPTY_FLUID = ResourceIdentifier.parse("minecraft:empty");
    private static final ResourceIdentifier WATER = ResourceIdentifier.parse("minecraft:water");
    private static final ResourceIdentifier LAVA = ResourceIdentifier.parse("minecraft:lava");

    private SpatialQueries() {
    }

    /** Returns all known colliding blocks while retaining UNKNOWN when no known obstacle dominates it. */
    public static SpatialScanResult nearbyObstacles(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot query
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(query, "query");
        if (snapshot.worldEpoch() != expectedWorldEpoch || !snapshot.bounds().contains(query)) {
            return new SpatialScanResult(SpaceStatus.UNKNOWN, List.of(), List.of());
        }
        BlockGrid grid = boundedGrid(query);
        if (grid == null) {
            return new SpatialScanResult(SpaceStatus.UNKNOWN, List.of(), List.of());
        }
        List<BlockPosition> inspected = new ArrayList<>((int) grid.cellCount());
        List<BlockPosition> blocked = new ArrayList<>();
        List<SpaceStatus> statuses = new ArrayList<>((int) grid.cellCount());
        forEachOverlappingBlock(grid, position -> {
            inspected.add(position);
            SpaceStatus status = blockClearance(snapshot, expectedWorldEpoch, position, query);
            statuses.add(status);
            if (status == SpaceStatus.BLOCKED) {
                blocked.add(position);
            }
        });
        return new SpatialScanResult(SpaceStatus.allOf(statuses), inspected, blocked);
    }

    public static SpatialScanResult nearbyObstacles(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BlockPosition center,
            int horizontalRadius,
            int verticalRadius
    ) {
        Objects.requireNonNull(center, "center");
        if (horizontalRadius < 0 || verticalRadius < 0) {
            throw new IllegalArgumentException("nearby radii must not be negative");
        }
        BoxSnapshot query = new BoxSnapshot(
                (double) center.x() - horizontalRadius,
                (double) center.y() - verticalRadius,
                (double) center.z() - horizontalRadius,
                (double) center.x() + horizontalRadius + 1.0D,
                (double) center.y() + verticalRadius + 1.0D,
                (double) center.z() + horizontalRadius + 1.0D);
        return nearbyObstacles(snapshot, expectedWorldEpoch, query);
    }

    public static SpaceStatus clearance(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot body
    ) {
        return nearbyObstacles(snapshot, expectedWorldEpoch, body).status();
    }

    /** A support result is PASSABLE only when a known collision box exists directly beneath the body. */
    public static SpaceStatus support(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot body
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(body, "body");
        BoxSnapshot probe = new BoxSnapshot(
                body.minX(), body.minY() - SUPPORT_PROBE_DEPTH, body.minZ(),
                body.maxX(), body.minY(), body.maxZ());
        if (snapshot.worldEpoch() != expectedWorldEpoch || !snapshot.bounds().contains(probe)) {
            return SpaceStatus.UNKNOWN;
        }
        BlockGrid grid = boundedGrid(probe);
        if (grid == null) {
            return SpaceStatus.UNKNOWN;
        }
        List<SpaceStatus> alternatives = new ArrayList<>((int) grid.cellCount());
        forEachOverlappingBlock(grid, position -> {
            Observation<BlockStateSnapshot> observed = snapshot.block(expectedWorldEpoch, position);
            if (!observed.isPresent() || !observed.get().collision().isPresent()) {
                alternatives.add(SpaceStatus.UNKNOWN);
                return;
            }
            alternatives.add(observed.get().collision().get().support(position, probe));
        });
        return SpaceStatus.anyOf(alternatives);
    }

    /** Walkability requires both body clearance and known solid support. */
    public static SpaceStatus walkability(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot body
    ) {
        return SpaceStatus.allOf(
                clearance(snapshot, expectedWorldEpoch, body),
                support(snapshot, expectedWorldEpoch, body));
    }

    /**
     * Garden void check. Only a completely known unsupported column through y=65 is BLOCKED;
     * any missing block or collision shape is UNKNOWN.
     */
    public static SpaceStatus gardenVoid(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BlockPosition from
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(from, "from");
        if (snapshot.worldEpoch() != expectedWorldEpoch || from.y() <= GARDEN_VOID_MIN_Y) {
            return SpaceStatus.UNKNOWN;
        }
        long cellCount;
        try {
            cellCount = Math.addExact(
                    Math.subtractExact((long) from.y(), (long) GARDEN_VOID_MIN_Y), 1L);
        } catch (ArithmeticException exception) {
            return SpaceStatus.UNKNOWN;
        }
        if (cellCount <= 0L || cellCount > SpatialCaptureRequest.MAX_BLOCKS) {
            return SpaceStatus.UNKNOWN;
        }
        for (int y = from.y(); y >= GARDEN_VOID_MIN_Y; y--) {
            BlockPosition position = new BlockPosition(from.x(), y, from.z());
            Observation<BlockStateSnapshot> observed = snapshot.block(expectedWorldEpoch, position);
            if (!observed.isPresent() || !observed.get().collision().isPresent()) {
                return SpaceStatus.UNKNOWN;
            }
            if (observed.get().collision().get().boxes().stream().anyMatch(BoxSnapshot::hasPositiveVolume)) {
                return SpaceStatus.PASSABLE;
            }
        }
        return SpaceStatus.BLOCKED;
    }

    /** Drop safety requires the conservative swept volume to be clear and the landing to have support. */
    public static SpaceStatus drop(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot start,
            BoxSnapshot landing
    ) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(landing, "landing");
        return SpaceStatus.allOf(
                clearance(snapshot, expectedWorldEpoch, start.span(landing)),
                support(snapshot, expectedWorldEpoch, landing));
    }

    /**
     * Scans walkability forward until a known obstruction (BLOCKED). Missing data aborts as UNKNOWN,
     * and reaching the hard 180-block budget without a conclusion is also UNKNOWN.
     */
    public static SpatialScanResult scanForwardUntilBlocked(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BoxSnapshot startingBody,
            RelativeFrame frame,
            int budget
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(startingBody, "startingBody");
        Objects.requireNonNull(frame, "frame");
        if (budget < 1 || budget > MAX_END_ROW_BLOCKS) {
            throw new IllegalArgumentException("end-row budget must be in [1, 180]");
        }
        List<BlockPosition> inspected = new ArrayList<>();
        for (int distance = 1; distance <= budget; distance++) {
            BoxSnapshot body = startingBody.move(
                    frame.forwardX() * (double) distance,
                    0.0D,
                    frame.forwardZ() * (double) distance);
            BlockPosition position = new BlockPosition(
                    checkedFloor((body.minX() + body.maxX()) / 2.0D),
                    checkedFloor(body.minY()),
                    checkedFloor((body.minZ() + body.maxZ()) / 2.0D));
            inspected.add(position);
            SpaceStatus status = walkability(snapshot, expectedWorldEpoch, body);
            if (status == SpaceStatus.BLOCKED) {
                return new SpatialScanResult(SpaceStatus.BLOCKED, inspected, List.of(position));
            }
            if (status == SpaceStatus.UNKNOWN) {
                return new SpatialScanResult(SpaceStatus.UNKNOWN, inspected, List.of());
            }
        }
        return new SpatialScanResult(SpaceStatus.UNKNOWN, inspected, List.of());
    }

    private static SpaceStatus blockClearance(
            SpatialSnapshot snapshot,
            long expectedWorldEpoch,
            BlockPosition position,
            BoxSnapshot query
    ) {
        Observation<BlockStateSnapshot> observed = snapshot.block(expectedWorldEpoch, position);
        if (!observed.isPresent()) {
            return SpaceStatus.UNKNOWN;
        }
        BlockStateSnapshot state = observed.get();
        SpaceStatus collision = state.collision().isPresent()
                ? state.collision().get().clearance(position, query)
                : SpaceStatus.UNKNOWN;
        return SpaceStatus.allOf(fluidStatus(state.fluidId()), collision);
    }

    private static SpaceStatus fluidStatus(ResourceIdentifier fluidId) {
        if (EMPTY_FLUID.equals(fluidId) || WATER.equals(fluidId)) {
            return SpaceStatus.PASSABLE;
        }
        if (LAVA.equals(fluidId)) {
            return SpaceStatus.BLOCKED;
        }
        return SpaceStatus.UNKNOWN;
    }

    private static BlockGrid boundedGrid(BoxSnapshot box) {
        if (!box.hasPositiveVolume()) {
            return null;
        }
        try {
            long minX = checkedFloor(box.minX());
            long minY = checkedFloor(box.minY());
            long minZ = checkedFloor(box.minZ());
            long maxXExclusive = checkedCeil(box.maxX());
            long maxYExclusive = checkedCeil(box.maxY());
            long maxZExclusive = checkedCeil(box.maxZ());
            long sizeX = Math.subtractExact(maxXExclusive, minX);
            long sizeY = Math.subtractExact(maxYExclusive, minY);
            long sizeZ = Math.subtractExact(maxZExclusive, minZ);
            if (sizeX <= 0L || sizeY <= 0L || sizeZ <= 0L) {
                return null;
            }
            long cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            if (cellCount <= 0L || cellCount > SpatialCaptureRequest.MAX_BLOCKS) {
                return null;
            }
            return new BlockGrid(minX, minY, minZ, maxXExclusive, maxYExclusive, maxZExclusive, cellCount);
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static void forEachOverlappingBlock(BlockGrid grid, BlockConsumer consumer) {
        for (long x = grid.minX(); x < grid.maxXExclusive(); x++) {
            for (long y = grid.minY(); y < grid.maxYExclusive(); y++) {
                for (long z = grid.minZ(); z < grid.maxZExclusive(); z++) {
                    consumer.accept(new BlockPosition((int) x, (int) y, (int) z));
                }
            }
        }
    }

    private static int checkedFloor(double value) {
        double result = Math.floor(value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new ArithmeticException("coordinate lies outside the integer block range");
        }
        return (int) result;
    }

    private static long checkedCeil(double value) {
        double result = Math.ceil(value);
        if (result < Integer.MIN_VALUE || result > (double) Integer.MAX_VALUE + 1.0D) {
            throw new ArithmeticException("coordinate lies outside the integer block range");
        }
        return (long) result;
    }

    private record BlockGrid(
            long minX,
            long minY,
            long minZ,
            long maxXExclusive,
            long maxYExclusive,
            long maxZExclusive,
            long cellCount
    ) {
    }

    @FunctionalInterface
    private interface BlockConsumer {
        void accept(BlockPosition position);
    }
}
