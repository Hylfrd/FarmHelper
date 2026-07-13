package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;

/** Validated persistence boundary used by the settings screen. */
@FunctionalInterface
public interface SettingsSaveService {
    boolean save(FarmHelperConfig config);
}
