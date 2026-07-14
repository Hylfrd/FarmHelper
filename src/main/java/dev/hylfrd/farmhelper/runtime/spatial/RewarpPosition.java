package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable rewarp-point geometry; persistence and configuration remain outside T7. */
public record RewarpPosition(int x, int y, int z) {
    public static RewarpPosition from(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        return new RewarpPosition(position.x(), position.y(), position.z());
    }

    public BlockPosition block() {
        return new BlockPosition(x, y, z);
    }

    public double squaredDistance(BlockPosition other) {
        Objects.requireNonNull(other, "other");
        double dx = (double) other.x() - x;
        double dy = (double) other.y() - y;
        double dz = (double) other.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(BlockPosition other) {
        return Math.sqrt(squaredDistance(other));
    }

    public static Optional<RewarpPosition> nearest(List<RewarpPosition> positions, BlockPosition origin) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(origin, "origin");
        return List.copyOf(positions).stream()
                .min(java.util.Comparator.comparingDouble(position -> position.squaredDistance(origin)));
    }
}
