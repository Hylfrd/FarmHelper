package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;

import java.util.Objects;

/** Complete persisted spawn pose consumed by runtime warp and diagnostics. */
public record MacroSpawnPose(RewarpPosition position, float yaw, float pitch, int plot) {
    public MacroSpawnPose {
        Objects.requireNonNull(position, "position");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException("spawn angles must be finite and pitch must be in [-90, 90]");
        }
        if (plot < -1 || plot > 24) {
            throw new IllegalArgumentException("spawn plot must be -1 or in [0, 24]");
        }
    }

    public dev.hylfrd.farmhelper.runtime.spatial.BlockPosition block() {
        return position.block();
    }

    public double squaredDistance(dev.hylfrd.farmhelper.runtime.spatial.BlockPosition block) {
        return position.squaredDistance(block);
    }
}
