package dev.hylfrd.farmhelper.runtime.spatial;

/** Immutable continuous AABB. Positive-volume intersection deliberately excludes boundary contact. */
public record BoxSnapshot(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) {
    public BoxSnapshot {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("maximum coordinates must not be less than minimum coordinates");
        }
    }

    public boolean hasPositiveVolume() {
        return maxX > minX && maxY > minY && maxZ > minZ;
    }

    public boolean intersects(BoxSnapshot other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public boolean contains(BoxSnapshot other) {
        return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
    }

    public BoxSnapshot move(double x, double y, double z) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        return new BoxSnapshot(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** Smallest AABB containing both boxes; useful for a conservative swept volume. */
    public BoxSnapshot span(BoxSnapshot other) {
        return new BoxSnapshot(
                Math.min(minX, other.minX),
                Math.min(minY, other.minY),
                Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX),
                Math.max(maxY, other.maxY),
                Math.max(maxZ, other.maxZ));
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double depth() {
        return maxZ - minZ;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
