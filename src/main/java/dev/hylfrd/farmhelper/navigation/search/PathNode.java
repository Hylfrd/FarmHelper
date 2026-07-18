package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;

import java.util.Objects;

/** One integer node in the upstream-compatible six-axis search lattice. */
public record PathNode(int x, int y, int z) {
    static PathNode startFor(BoxSnapshot playerBody) {
        Objects.requireNonNull(playerBody, "playerBody");
        return new PathNode(
                checkedFloor(playerBody.minX()),
                checkedFloor(playerBody.minY() + 0.5D),
                checkedFloor(playerBody.minZ()));
    }

    static PathNode targetFor(
            NavigationGoal goal,
            double entityWidth,
            double entityHeight
    ) {
        Objects.requireNonNull(goal, "goal");
        return new PathNode(
                checkedFloor(goal.x() - entityWidth / 2.0D),
                checkedFloor(goal.y() - entityHeight / 2.0D),
                checkedFloor(goal.z() - entityWidth / 2.0D));
    }

    float distanceTo(PathNode other) {
        return (float) Math.sqrt(squaredDistanceTo(other));
    }

    float squaredDistanceTo(PathNode other) {
        Objects.requireNonNull(other, "other");
        float dx = (float) (other.x - x);
        float dy = (float) (other.y - y);
        float dz = (float) (other.z - z);
        return dx * dx + dy * dy + dz * dz;
    }

    PathNode translated(int xOffset, int yOffset, int zOffset) {
        return new PathNode(
                Math.addExact(x, xOffset),
                Math.addExact(y, yOffset),
                Math.addExact(z, zOffset));
    }

    private static int checkedFloor(double value) {
        double floor = Math.floor(value);
        if (!Double.isFinite(value) || floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("path coordinate is outside the integer lattice");
        }
        return (int) floor;
    }
}
