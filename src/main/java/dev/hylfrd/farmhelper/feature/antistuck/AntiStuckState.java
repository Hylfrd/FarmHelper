package dev.hylfrd.farmhelper.feature.antistuck;

/** Upstream AntiStuck state names, kept independent from macro path recovery. */
public enum AntiStuckState {
    NONE,
    PRESS,
    RELEASE,
    COME_BACK,
    DISABLE
}
