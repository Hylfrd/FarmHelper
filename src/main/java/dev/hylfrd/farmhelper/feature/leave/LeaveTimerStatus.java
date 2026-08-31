package dev.hylfrd.farmhelper.feature.leave;

/** One coherent macro, failsafe, and feature-lifecycle observation for a leave-timer tick. */
public record LeaveTimerStatus(
        boolean macroActive,
        long macroGeneration,
        boolean failsafeActive,
        boolean otherFeatureActive
) {
    public LeaveTimerStatus {
        if (macroGeneration < 0L) {
            throw new IllegalArgumentException("macroGeneration must not be negative");
        }
        if (macroActive && macroGeneration == 0L) {
            throw new IllegalArgumentException("an active macro must have a positive generation");
        }
    }

    public static LeaveTimerStatus active(
            long macroGeneration,
            boolean failsafeActive,
            boolean otherFeatureActive
    ) {
        return new LeaveTimerStatus(
                true, macroGeneration, failsafeActive, otherFeatureActive);
    }

    public static LeaveTimerStatus inactive() {
        return new LeaveTimerStatus(false, 0L, false, false);
    }
}
