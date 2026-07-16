package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.List;
import java.util.Objects;

/** Immutable block-local collision boxes captured from a modern VoxelShape. */
public record CollisionShapeSnapshot(List<BoxSnapshot> boxes) {
    /**
     * Audited Minecraft 26.1.2 bound for collision geometry outside a block unit cube.
     * PistonMovingBlockEntity translates a moved state's shape by at most one block. Auditing all
     * movable states gives base X/Z [0,1] and Y [0,1.5], so moving shapes are bounded by X/Z
     * [-1,2] and Y [-1,2.5]. Corrupt progress data is rejected instead of widening this policy.
     */
    public static final double MAX_LOCAL_PROTRUSION = 1.5D;
    public static final double MIN_HORIZONTAL_LOCAL_COORDINATE = -1.0D;
    public static final double MAX_HORIZONTAL_LOCAL_COORDINATE = 2.0D;
    public static final double MIN_VERTICAL_LOCAL_COORDINATE = -1.0D;
    public static final double MAX_VERTICAL_LOCAL_COORDINATE = 1.0D + MAX_LOCAL_PROTRUSION;
    public static final CollisionShapeSnapshot EMPTY = new CollisionShapeSnapshot(List.of());

    public CollisionShapeSnapshot {
        Objects.requireNonNull(boxes, "boxes");
        boxes = List.copyOf(boxes);
        for (BoxSnapshot box : boxes) {
            Objects.requireNonNull(box, "collision box");
            if (!box.hasPositiveVolume()
                    || box.minX() < MIN_HORIZONTAL_LOCAL_COORDINATE
                    || box.minY() < MIN_VERTICAL_LOCAL_COORDINATE
                    || box.minZ() < MIN_HORIZONTAL_LOCAL_COORDINATE
                    || box.maxX() > MAX_HORIZONTAL_LOCAL_COORDINATE
                    || box.maxY() > MAX_VERTICAL_LOCAL_COORDINATE
                    || box.maxZ() > MAX_HORIZONTAL_LOCAL_COORDINATE) {
                throw new IllegalArgumentException(
                        "collision box exceeds the audited Minecraft local-shape bound");
            }
        }
    }

    public SpaceStatus clearance(BlockPosition block, BoxSnapshot query) {
        for (BoxSnapshot box : boxes) {
            if (box.move(block.x(), block.y(), block.z()).intersects(query)) {
                return SpaceStatus.BLOCKED;
            }
        }
        return SpaceStatus.PASSABLE;
    }

    public SpaceStatus support(BlockPosition block, BoxSnapshot probe) {
        for (BoxSnapshot box : boxes) {
            if (box.move(block.x(), block.y(), block.z()).intersects(probe)) {
                return SpaceStatus.PASSABLE;
            }
        }
        return SpaceStatus.BLOCKED;
    }
}
