package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.macro.MacroRandom;

import java.util.Objects;

/** Independent entropy seam for rotation handler modifier and minimum-floor draws. */
public final class RotationEntropy {
    private final MacroRandom random;

    public RotationEntropy(MacroRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public float backModifier() {
        return (float) (unit() * 0.5D - 0.25D);
    }

    public long minimumFloorMillis() {
        return 50L + (long) Math.floor(unit() * 100.0D);
    }

    private double unit() {
        double value = random.nextUnit();
        if (!Double.isFinite(value) || value < 0.0D || value >= 1.0D) {
            throw new IllegalStateException("rotation entropy draw must be in [0, 1)");
        }
        return value;
    }
}
