package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

/** Stable owner identity shared by the pure macro decision and client control adapters. */
public final class MacroControlOwner {
    public static final ControlOwner S_SHAPE = new ControlOwner("s-shape-vertical-macro");

    private MacroControlOwner() {
    }
}
