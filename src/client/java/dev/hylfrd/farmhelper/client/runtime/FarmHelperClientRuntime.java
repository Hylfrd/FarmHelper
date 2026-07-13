package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.client.control.ClientInputController;
import dev.hylfrd.farmhelper.client.control.ClientRotationController;
import dev.hylfrd.farmhelper.config.ConfigLoadResult;
import dev.hylfrd.farmhelper.config.ConfigLoadStatus;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.config.FarmHelperConfigStore;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.runtime.FarmHelperRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Composition root and sole owner of mutable services for one client session. */
public final class FarmHelperClientRuntime {
    private final FarmHelperConfigStore configStore;
    private final ConfigLoadResult configLoadResult;
    private final FarmHelperRuntime core;
    private final ClientInputController input = new ClientInputController();
    private final ClientRotationController rotation = new ClientRotationController();

    public FarmHelperClientRuntime() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FarmHelper.MOD_ID + ".json"));
    }

    FarmHelperClientRuntime(Path configPath) {
        configStore = new FarmHelperConfigStore(configPath);
        configLoadResult = configStore.load();
        core = new FarmHelperRuntime(configLoadResult.config());
        logConfigLoadResult(configLoadResult);
    }

    public FarmHelperRuntime core() {
        return core;
    }

    public ClientInputController input() {
        return input;
    }

    public ClientRotationController rotation() {
        return rotation;
    }

    public ConfigLoadResult configLoadResult() {
        return configLoadResult;
    }

    public boolean setTargetYaw(float value) {
        return updateConfig(config -> config.setTargetYaw(value));
    }

    public boolean setTargetPitch(float value) {
        return updateConfig(config -> config.setTargetPitch(value));
    }

    public float configValue(FarmHelperConfigKey key) {
        return key.read(core.config());
    }

    public boolean setConfig(FarmHelperConfigKey key, float value) {
        return updateConfig(config -> key.write(config, value));
    }

    public boolean resetConfig(FarmHelperConfigKey key) {
        return updateConfig(key::reset);
    }

    public boolean resetConfig() {
        return updateConfig(FarmHelperConfig::reset);
    }

    public FarmHelperConfig configSnapshot() {
        return core.config().copy();
    }

    public boolean saveConfig(FarmHelperConfig replacement) {
        FarmHelperConfig snapshot = replacement.copy();
        return updateConfig(config -> config.replaceWith(snapshot));
    }

    public void stop(Minecraft client) {
        core.macroManager().stop();
        rotation.stop();
        input.releaseAll(client);
    }

    public boolean reset(Minecraft client) {
        stop(client);
        return resetConfig();
    }

    private boolean updateConfig(Consumer<FarmHelperConfig> update) {
        FarmHelperConfig before = core.config().copy();
        try {
            update.accept(core.config());
            configStore.save(core.config());
            return true;
        } catch (IOException | RuntimeException exception) {
            core.config().replaceWith(before);
            FarmHelper.LOGGER.error("Failed to update or save FarmHelper configuration: {}", exception.toString());
            return false;
        }
    }

    private static void logConfigLoadResult(ConfigLoadResult result) {
        if (result.status() == ConfigLoadStatus.LOADED) {
            FarmHelper.LOGGER.info("Loaded FarmHelper configuration schema {}.", result.config().schemaVersion());
            return;
        }
        if (result.status() == ConfigLoadStatus.DEFAULTS_NOT_SAVED) {
            FarmHelper.LOGGER.error("FarmHelper is using unsaved default configuration: {}", result.diagnostic());
            return;
        }
        FarmHelper.LOGGER.warn("FarmHelper configuration status {}: {}", result.status(), result.diagnostic());
    }
}
