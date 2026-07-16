package dev.hylfrd.farmhelper.macro;

/** Exact movement posture needed by drop and post-warp landing ledgers. */
public record PlayerPosture(boolean flying, boolean onGround, boolean suffocating) {
    public PlayerPosture(boolean flying, boolean onGround) {
        this(flying, onGround, false);
    }
}
