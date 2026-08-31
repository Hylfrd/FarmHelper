package dev.hylfrd.farmhelper.feature.lifecycle;

/** Whether detector evidence is admissible while a feature is active. */
public enum FeatureFailsafePolicy {
    ALLOW_CHECKS,
    SUPPRESS_CHECKS
}
