package dev.hylfrd.farmhelper.feature.usage;

/** Explicit, privacy-safe tick evidence supplied by a later lifecycle adapter. */
public record UsageTick(long nowNanos, boolean macroActive) {
    public UsageTick {
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must be non-negative");
        }
    }

    public static UsageTick active(long nowNanos) {
        return new UsageTick(nowNanos, true);
    }

    public static UsageTick idle(long nowNanos) {
        return new UsageTick(nowNanos, false);
    }
}
