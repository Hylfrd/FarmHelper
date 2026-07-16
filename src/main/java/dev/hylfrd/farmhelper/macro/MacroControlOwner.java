package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

/** Stable owner identity shared by the pure macro decision and client control adapters. */
public final class MacroControlOwner {
    public static final ControlOwner FARMING = new ControlOwner("farming-macro");
    /** Compatibility alias only; production ownership paths use {@link #FARMING}. */
    @Deprecated(forRemoval = false)
    public static final ControlOwner S_SHAPE = FARMING;

    private MacroControlOwner() {
    }
}
