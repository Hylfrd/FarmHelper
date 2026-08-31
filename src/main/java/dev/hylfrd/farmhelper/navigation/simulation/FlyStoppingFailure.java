package dev.hylfrd.farmhelper.navigation.simulation;

/** Typed reasons why a bounded prediction cannot return a trustworthy state. */
public enum FlyStoppingFailure {
    INVALID_STATE(false),
    MISSING_EVIDENCE(true),
    STALE_EVIDENCE(true),
    UNLOADED_EVIDENCE(true),
    INCOMPLETE_EVIDENCE(true);

    private final boolean evidenceFailure;

    FlyStoppingFailure(boolean evidenceFailure) {
        this.evidenceFailure = evidenceFailure;
    }

    public boolean evidenceFailure() {
        return evidenceFailure;
    }
}
