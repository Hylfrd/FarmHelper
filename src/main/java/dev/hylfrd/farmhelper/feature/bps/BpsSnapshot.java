package dev.hylfrd.farmhelper.feature.bps;

/** One immutable view of the tracker's active-time rolling window. */
public record BpsSnapshot(
        double blocksPerSecond,
        int retainedBlocks,
        long elapsedNanos,
        boolean capacityLimited
) {
    public static final BpsSnapshot ZERO = new BpsSnapshot(0.0D, 0, 0L, false);

    public BpsSnapshot {
        if (!Double.isFinite(blocksPerSecond) || blocksPerSecond < 0.0D) {
            throw new IllegalArgumentException("blocksPerSecond must be finite and non-negative");
        }
        if (retainedBlocks < 0) {
            throw new IllegalArgumentException("retainedBlocks must be non-negative");
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
        if (elapsedNanos == 0L && blocksPerSecond != 0.0D) {
            throw new IllegalArgumentException("zero elapsed time cannot have a non-zero rate");
        }
    }
}
