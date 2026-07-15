package dev.hylfrd.farmhelper.macro;

import java.util.Objects;

public record MacroWarpRequest(boolean developmentWorld, MacroSpawnPose spawn) {
    public MacroWarpRequest {
        Objects.requireNonNull(spawn, "spawn");
    }
}
