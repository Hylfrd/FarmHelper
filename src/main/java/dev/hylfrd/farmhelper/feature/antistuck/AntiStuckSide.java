package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;

/** Horizontal block sides in the exact upstream multiplier and tie-break order. */
public enum AntiStuckSide {
    SOUTH(new AntiStuckPoint(0.5D, 0.5D, 1.0D), new AntiStuckPoint(0.0D, 0.0D, 1.0D)),
    WEST(new AntiStuckPoint(0.0D, 0.5D, 0.5D), new AntiStuckPoint(-1.0D, 0.0D, 0.0D)),
    NORTH(new AntiStuckPoint(0.5D, 0.5D, 0.0D), new AntiStuckPoint(0.0D, 0.0D, -1.0D)),
    EAST(new AntiStuckPoint(1.0D, 0.5D, 0.5D), new AntiStuckPoint(1.0D, 0.0D, 0.0D));

    private final AntiStuckPoint multiplier;
    private final AntiStuckPoint direction;

    AntiStuckSide(AntiStuckPoint multiplier, AntiStuckPoint direction) {
        this.multiplier = multiplier;
        this.direction = direction;
    }

    /** Exact side-center multiplier from the upstream AntiStuck implementation. */
    public AntiStuckPoint multiplier() {
        return multiplier;
    }

    public BlockPosition adjacent(BlockPosition obstacle) {
        if (obstacle == null) {
            throw new NullPointerException("obstacle");
        }
        return obstacle.offset((int) direction.x(), (int) direction.y(), (int) direction.z());
    }

    public AntiStuckPoint sideCenter(BlockPosition obstacle) {
        if (obstacle == null) {
            throw new NullPointerException("obstacle");
        }
        return new AntiStuckPoint(
                obstacle.x() + multiplier.x(),
                obstacle.y() + multiplier.y(),
                obstacle.z() + multiplier.z());
    }

    /** Target one block beyond the selected side, matching getMovementTarget exactly. */
    public AntiStuckPoint movementTarget(BlockPosition obstacle) {
        return sideCenter(obstacle).add(direction);
    }
}
