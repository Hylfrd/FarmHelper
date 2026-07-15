package dev.hylfrd.farmhelper.client.ui.command;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.config.ConfigLoadResult;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.PlayerSnapshot;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.ui.command.CommandActionResult;
import dev.hylfrd.farmhelper.ui.command.FarmHelperCommandService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Fabric client adapter. Every operation delegates to an existing owner or injected UI opener. */
public final class ClientFarmHelperCommandService implements FarmHelperCommandService {
    private final FarmHelperClientRuntime runtime;
    private final SettingsScreenOpener settingsScreenOpener;
    private final ClientCommandScreenCloseGuard commandScreenClose;
    private final Supplier<Object> currentScreen;
    private final Predicate<Object> chatScreen;
    private final Runnable manualStop;
    private final BooleanSupplier runtimeReset;
    private final RotationStarter rotationStarter;

    public ClientFarmHelperCommandService(
            FarmHelperClientRuntime runtime,
            SettingsScreenOpener settingsScreenOpener,
            ClientCommandScreenCloseGuard commandScreenClose
    ) {
        this(runtime, settingsScreenOpener, commandScreenClose,
                ClientFarmHelperCommandService::currentMinecraftScreen,
                screen -> screen instanceof ChatScreen,
                () -> runtime.stop(Minecraft.getInstance()),
                () -> runtime.reset(Minecraft.getInstance()),
                (yaw, pitch, durationMs) -> runtime.rotation().start(
                        Minecraft.getInstance(), yaw, pitch, durationMs));
    }

    ClientFarmHelperCommandService(
            FarmHelperClientRuntime runtime,
            SettingsScreenOpener settingsScreenOpener,
            ClientCommandScreenCloseGuard commandScreenClose,
            Supplier<Object> currentScreen,
            Predicate<Object> chatScreen,
            Runnable manualStop,
            BooleanSupplier runtimeReset,
            RotationStarter rotationStarter
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settingsScreenOpener = Objects.requireNonNull(settingsScreenOpener, "settingsScreenOpener");
        this.commandScreenClose = Objects.requireNonNull(commandScreenClose, "commandScreenClose");
        this.currentScreen = Objects.requireNonNull(currentScreen, "currentScreen");
        this.chatScreen = Objects.requireNonNull(chatScreen, "chatScreen");
        this.manualStop = Objects.requireNonNull(manualStop, "manualStop");
        this.runtimeReset = Objects.requireNonNull(runtimeReset, "runtimeReset");
        this.rotationStarter = Objects.requireNonNull(rotationStarter, "rotationStarter");
    }

    @Override
    public List<String> status() {
        MacroManager macro = runtime.core().macroManager();
        FarmHelperConfig config = runtime.core().config();
        return List.of(
                "Enabled: " + macro.enabled() + ", state: " + lower(macro.state()),
                "Macro: " + macro.activeMacroId() + ", ticks: " + macro.runningTicks(),
                "World: " + lower(macro.worldMode()) + ", pause: " + lower(macro.pauseReason()),
                "Target yaw: " + config.targetYaw() + ", pitch: " + config.targetPitch());
    }

    @Override
    public CommandActionResult toggle() {
        boolean enabled = runtime.toggle();
        armExpectedClose();
        return CommandActionResult.success("Macro " + (enabled ? "enabled" : "disabled") + ".");
    }

    @Override
    public CommandActionResult stop() {
        manualStop.run();
        armExpectedClose();
        return CommandActionResult.success("Macro disabled and controls released.");
    }

    @Override
    public CommandActionResult reset() {
        boolean saved = runtimeReset.getAsBoolean();
        armExpectedClose();
        return saved
                ? CommandActionResult.success("Runtime stopped and configuration reset.")
                : CommandActionResult.failure("Reset failed; previous configuration was restored.");
    }

    @Override
    public float configValue(FarmHelperConfigKey key) {
        return runtime.configValue(key);
    }

    @Override
    public CommandActionResult setConfig(FarmHelperConfigKey key, float value) {
        boolean saved = runtime.setConfig(key, value);
        return saved
                ? CommandActionResult.success(key.commandName() + " set to " + runtime.configValue(key) + ".")
                : CommandActionResult.failure(key.commandName() + " was not changed: save failed.");
    }

    @Override
    public CommandActionResult resetConfig(FarmHelperConfigKey key) {
        boolean saved = runtime.resetConfig(key);
        return saved
                ? CommandActionResult.success(key.commandName() + " reset to " + runtime.configValue(key) + ".")
                : CommandActionResult.failure(key.commandName() + " was not reset: save failed.");
    }

    @Override
    public CommandActionResult resetConfig() {
        boolean saved = runtime.resetConfig();
        return saved
                ? CommandActionResult.success("Configuration reset to defaults.")
                : CommandActionResult.failure("Configuration reset failed; previous values were restored.");
    }

    @Override
    public CommandActionResult openConfig() {
        try {
            return settingsScreenOpener.open()
                    ? CommandActionResult.success("Settings screen opened.")
                    : CommandActionResult.failure("Settings screen is not available in this build.");
        } catch (RuntimeException exception) {
            FarmHelper.LOGGER.error("Failed to open FarmHelper settings: {}", exception.toString());
            return CommandActionResult.failure("Settings screen could not be opened.");
        }
    }

    @Override
    public CommandActionResult testRotation(float yaw, float pitch, int durationMs) {
        boolean started = rotationStarter.start(yaw, pitch, durationMs);
        if (started) {
            armExpectedClose();
        } else {
            commandScreenClose.clear();
        }
        return started
                ? CommandActionResult.success("Rotation test started.")
                : CommandActionResult.failure("Rotation test failed: no player.");
    }

    @Override
    public CommandActionResult releaseInput() {
        runtime.input().releaseAll(Minecraft.getInstance());
        return CommandActionResult.success("Input released.");
    }

    @Override
    public List<String> diagnostics() {
        MacroManager macro = runtime.core().macroManager();
        PlayerSnapshot player = macro.playerSnapshot();
        ConfigLoadResult load = runtime.configLoadResult();
        List<String> lines = new ArrayList<>();
        lines.add("Config: " + lower(load.status()) + ", schema: " + load.config().schemaVersion());
        load.backup().ifPresent(path -> lines.add("Config backup: " + path.getFileName()));
        if (!load.diagnostic().isBlank()) {
            lines.add("Config diagnostic: " + load.diagnostic());
        }
        lines.add(player.rotationDiagnostic());
        lines.add("Rotation active: " + runtime.rotation().rotating()
                + ", paused: " + runtime.rotation().paused());
        lines.add("Held input: " + runtime.input().heldKeysText());
        return List.copyOf(lines);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private void armExpectedClose() {
        Object screen = currentScreen.get();
        commandScreenClose.armAfterCommand(
                screen, chatScreen.test(screen), runtime.ownershipGeneration());
    }

    private static Object currentMinecraftScreen() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.screen;
    }

    @FunctionalInterface
    interface RotationStarter {
        boolean start(float yaw, float pitch, int durationMs);
    }

}
