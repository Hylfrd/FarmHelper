package dev.hylfrd.farmhelper.feature.lifecycle;

/** Manager-owned runtime state; configuration eligibility is deliberately separate. */
public enum FeatureState {
    STOPPED,
    RUNNING,
    PAUSED;

    public boolean active() {
        return this != STOPPED;
    }
}
