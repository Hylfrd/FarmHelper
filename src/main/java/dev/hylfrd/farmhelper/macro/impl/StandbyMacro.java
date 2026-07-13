package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.macro.Macro;
import dev.hylfrd.farmhelper.macro.MacroContext;

public final class StandbyMacro implements Macro {
    @Override
    public String id() {
        return "standby";
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onStop() {
    }

    @Override
    public void tick(MacroContext context) {
    }
}
