package dev.hylfrd.farmhelper.ui.command;

import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;

import java.util.List;

/**
 * Port used by the command tree. Implementations may only delegate to runtime, config, control,
 * and presentation owners; command code must not become another owner of product state.
 */
public interface FarmHelperCommandService {
    List<String> status();

    CommandActionResult toggle();

    CommandActionResult stop();

    CommandActionResult reset();

    float configValue(FarmHelperConfigKey key);

    CommandActionResult setConfig(FarmHelperConfigKey key, float value);

    CommandActionResult resetConfig(FarmHelperConfigKey key);

    CommandActionResult resetConfig();

    CommandActionResult openConfig();

    CommandActionResult testRotation(float yaw, float pitch, int durationMs);

    CommandActionResult releaseInput();

    List<String> diagnostics();
}
