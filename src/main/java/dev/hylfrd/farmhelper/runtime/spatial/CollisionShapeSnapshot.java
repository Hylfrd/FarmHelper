package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.List;
import java.util.Objects;

/** Immutable block-local collision boxes captured from a modern VoxelShape. */
public record CollisionShapeSnapshot(List<BoxSnapshot> boxes) {
    public static final CollisionShapeSnapshot EMPTY = new CollisionShapeSnapshot(List.of());

    public CollisionShapeSnapshot {
        Objects.requireNonNull(boxes, "boxes");
        boxes = List.copyOf(boxes);
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
