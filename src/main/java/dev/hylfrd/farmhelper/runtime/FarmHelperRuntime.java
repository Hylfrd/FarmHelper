package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.macro.MacroManager;

/** Owns the version-independent mutable services for one FarmHelper runtime. */
public final class FarmHelperRuntime {
    private final FarmHelperConfig config;
    private final MacroManager macroManager;

    public FarmHelperRuntime() {
        this(new FarmHelperConfig(), new MacroManager());
    }

    public FarmHelperRuntime(FarmHelperConfig config) {
        this(config, new MacroManager());
    }

    FarmHelperRuntime(FarmHelperConfig config, MacroManager macroManager) {
        this.config = config;
        this.macroManager = macroManager;
    }

    public FarmHelperConfig config() {
        return config;
    }

    public MacroManager macroManager() {
        return macroManager;
    }
}
