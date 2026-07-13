package dev.hylfrd.farmhelper.macro;

public interface Macro {
    String id();

    void onStart();

    void onStop();

    void tick(MacroContext context);
}
