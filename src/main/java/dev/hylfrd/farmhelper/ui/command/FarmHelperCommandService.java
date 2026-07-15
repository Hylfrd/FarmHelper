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

    default CommandActionResult setMacroMode(int code) {
        return CommandActionResult.failure("Macro mode is unavailable.");
    }

    default CommandActionResult startMacro() {
        return CommandActionResult.failure("Macro start is unavailable.");
    }

    default CommandActionResult pauseMacro() {
        return CommandActionResult.failure("Macro pause is unavailable.");
    }

    default CommandActionResult resumeMacro() {
        return CommandActionResult.failure("Macro resume is unavailable.");
    }

    default CommandActionResult stopMacro() {
        return stop();
    }

    default CommandActionResult setSpawn() {
        return CommandActionResult.failure("Spawn management is unavailable.");
    }

    default CommandActionResult addRewarp() {
        return CommandActionResult.failure("Rewarp management is unavailable.");
    }

    default CommandActionResult removeRewarp() {
        return CommandActionResult.failure("Rewarp management is unavailable.");
    }

    default CommandActionResult clearRewarps() {
        return CommandActionResult.failure("Rewarp management is unavailable.");
    }

    List<String> diagnostics();
}
