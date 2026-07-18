package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;

/** Immutable, Minecraft-free target evidence captured on the client thread. */
public record FollowTargetSnapshot(
        FollowTargetIdentity identity,
        long worldEpoch,
        PositionSnapshot position,
        MotionSnapshot motion
) {
    public static final double MOVING_AXIS_THRESHOLD = 0.15D;
    public static final double STATIONARY_APPROACH_DISTANCE = 1.2D;
    public static final double STATIONARY_HEIGHT_OFFSET = 0.5D;

    public FollowTargetSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(motion, "motion");
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }

    /**
     * Reproduces the fixed upstream target projection before request-level Y offset is applied.
     * Moving targets use one velocity step. Stationary targets approach from the follower side.
     */
    public NavigationGoal navigationGoal(PositionSnapshot followerPosition) {
        Objects.requireNonNull(followerPosition, "followerPosition");
        if (Math.abs(motion.x()) > MOVING_AXIS_THRESHOLD
                || Math.abs(motion.z()) > MOVING_AXIS_THRESHOLD) {
            return new NavigationGoal(
                    position.x() + motion.x(),
                    position.y() + motion.y(),
                    position.z() + motion.z());
        }

        double xDifference = followerPosition.x() - position.x();
        double zDifference = followerPosition.z() - position.z();
        double horizontalDistance = Math.hypot(xDifference, zDifference);
        // Java's atan2(0, 0) made the upstream direction +X. Retain that deterministic edge case.
        double directionX = horizontalDistance == 0.0D ? 1.0D
                : xDifference / horizontalDistance;
        double directionZ = horizontalDistance == 0.0D ? 0.0D
                : zDifference / horizontalDistance;
        return new NavigationGoal(
                position.x() + directionX * STATIONARY_APPROACH_DISTANCE,
                position.y() + STATIONARY_HEIGHT_OFFSET,
                position.z() + directionZ * STATIONARY_APPROACH_DISTANCE);
    }
}
