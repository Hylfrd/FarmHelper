package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure translation of AntiStuck's nearest-side and relative-key geometry. */
public final class AntiStuckGeometry {
    public static final double MOVEMENT_KEY_HALF_WIDTH_DEGREES = 67.5D;

    private AntiStuckGeometry() {
    }

    public static Optional<AntiStuckSide> nearestClearSide(
            BlockPosition obstacle,
            AntiStuckPlayerPose player,
            Set<AntiStuckSide> clearSides
    ) {
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clearSides, "clearSides");
        EnumSet<AntiStuckSide> copy = EnumSet.noneOf(AntiStuckSide.class);
        for (AntiStuckSide side : clearSides) {
            copy.add(Objects.requireNonNull(side, "clear side"));
        }
        return Arrays.stream(AntiStuckSide.values())
                .filter(copy::contains)
                .min((left, right) -> Double.compare(
                        horizontalDistance(player, left.sideCenter(obstacle)),
                        horizontalDistance(player, right.sideCenter(obstacle))));
    }

    public static AntiStuckPoint blockCenter(BlockPosition block) {
        Objects.requireNonNull(block, "block");
        return new AntiStuckPoint(block.x() + 0.5D, block.y() + 0.5D, block.z() + 0.5D);
    }

    /** Exact four-key threshold logic from the fixed upstream movement helper. */
    public static Set<InputAction> neededMovementKeys(
            AntiStuckPlayerPose player,
            AntiStuckPoint target
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");

        double deltaX = player.x() - target.x();
        double deltaZ = player.z() - target.z();
        double requiredAngle = Math.toDegrees(Math.atan2(deltaX, -deltaZ));
        double angleDifference = -normalizeUpstreamYaw(requiredAngle - player.yaw());

        EnumSet<InputAction> keys = EnumSet.noneOf(InputAction.class);
        addIfWithin(keys, InputAction.FORWARD, 0.0D, angleDifference);
        addIfWithin(keys, InputAction.LEFT, 90.0D, angleDifference);
        addIfWithin(keys, InputAction.BACKWARD, 180.0D, angleDifference);
        addIfWithin(keys, InputAction.RIGHT, -90.0D, angleDifference);
        return Set.copyOf(keys);
    }

    private static double horizontalDistance(AntiStuckPlayerPose player, AntiStuckPoint point) {
        return Math.hypot(player.x() - point.x(), player.z() - point.z());
    }

    private static void addIfWithin(
            EnumSet<InputAction> keys,
            InputAction key,
            double relativeYaw,
            double angleDifference
    ) {
        if (Math.abs(relativeYaw - angleDifference) < MOVEMENT_KEY_HALF_WIDTH_DEGREES
                || Math.abs(relativeYaw - (angleDifference + 360.0D))
                < MOVEMENT_KEY_HALF_WIDTH_DEGREES) {
            keys.add(key);
        }
    }

    private static double normalizeUpstreamYaw(double yaw) {
        double normalized = yaw % 360.0D;
        if (normalized < -180.0D) {
            normalized += 360.0D;
        }
        if (normalized > 180.0D) {
            normalized -= 360.0D;
        }
        return normalized;
    }
}
