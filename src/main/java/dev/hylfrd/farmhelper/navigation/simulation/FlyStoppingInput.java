package dev.hylfrd.farmhelper.navigation.simulation;

/** Immutable movement input copied into the predictor. Stopping states require {@link #ZERO}. */
public record FlyStoppingInput(double strafe, double forward) {
    public static final FlyStoppingInput ZERO = new FlyStoppingInput(0.0D, 0.0D);
    private static final double INPUT_DAMPING = 0.98F;

    public FlyStoppingInput {
        requireFinite(strafe, "strafe");
        requireFinite(forward, "forward");
    }

    public FlyStoppingInput damped() {
        if (isZero()) {
            return ZERO;
        }
        return new FlyStoppingInput(strafe * INPUT_DAMPING, forward * INPUT_DAMPING);
    }

    public boolean isZero() {
        return strafe == 0.0D && forward == 0.0D;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
