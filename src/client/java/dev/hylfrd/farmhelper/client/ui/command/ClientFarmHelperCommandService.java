package dev.hylfrd.farmhelper.client.ui.command;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.config.ConfigLoadResult;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.MacroMode;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.config.MacroLocationConfig;
import dev.hylfrd.farmhelper.runtime.spatial.GardenPlotMap;
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
    private final Supplier<MacroLocationConfig> currentPosition;

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
                        Minecraft.getInstance(), yaw, pitch, durationMs),
                ClientFarmHelperCommandService::currentMinecraftPosition);
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
        this(runtime, settingsScreenOpener, commandScreenClose, currentScreen, chatScreen,
                manualStop, runtimeReset, rotationStarter,
                ClientFarmHelperCommandService::currentMinecraftPosition);
    }

    ClientFarmHelperCommandService(
            FarmHelperClientRuntime runtime,
            SettingsScreenOpener settingsScreenOpener,
            ClientCommandScreenCloseGuard commandScreenClose,
            Supplier<Object> currentScreen,
            Predicate<Object> chatScreen,
            Runnable manualStop,
            BooleanSupplier runtimeReset,
            RotationStarter rotationStarter,
            Supplier<MacroLocationConfig> currentPosition
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settingsScreenOpener = Objects.requireNonNull(settingsScreenOpener, "settingsScreenOpener");
        this.commandScreenClose = Objects.requireNonNull(commandScreenClose, "commandScreenClose");
        this.currentScreen = Objects.requireNonNull(currentScreen, "currentScreen");
        this.chatScreen = Objects.requireNonNull(chatScreen, "chatScreen");
        this.manualStop = Objects.requireNonNull(manualStop, "manualStop");
        this.runtimeReset = Objects.requireNonNull(runtimeReset, "runtimeReset");
        this.rotationStarter = Objects.requireNonNull(rotationStarter, "rotationStarter");
        this.currentPosition = Objects.requireNonNull(currentPosition, "currentPosition");
    }

    @Override
    public List<String> status() {
        MacroManager macro = runtime.core().macroManager();
        FarmHelperConfig config = runtime.core().config();
        return List.of(
                "Enabled: " + macro.enabled() + ", state: " + lower(macro.state())
                        + ", generation: " + macro.generation() + ", terminal: "
                        + macro.lastTerminalReason().map(ClientFarmHelperCommandService::lower)
                        .orElse("none"),
                "Macro: " + macro.activeMacroId() + ", ticks: " + macro.runningTicks(),
                "Macro status: " + macro.lastStatus(),
                "Mode: " + macro.settings().macroMode().code() + ", rewarps: "
                        + macro.settings().rewarps().size() + ", spawn: "
                        + macro.settings().spawn().map(Object::toString).orElse("unset"),
                "Connection: " + connectionText(macro) + ", pauses: " + macro.pauseCauses(),
                "Server: " + lower(runtime.core().serverResponsiveness(
                        macro.clientSnapshot().connection().isPresent())),
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
        if (runtime.core().macroManager().enabled()) {
            return CommandActionResult.failure("Stop the macro before resetting configuration.");
        }
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
    public CommandActionResult setMacroMode(int code) {
        if (MacroMode.fromCode(code).isEmpty()) {
            return CommandActionResult.failure("Unknown macro mode; use a code from 0 through 13.");
        }
        if (runtime.core().macroManager().enabled()) {
            return CommandActionResult.failure("Stop the macro before changing mode.");
        }
        return runtime.setMacroMode(code)
                ? CommandActionResult.success("Macro mode set to " + code + ".")
                : CommandActionResult.failure("Macro mode was not saved.");
    }

    @Override
    public CommandActionResult startMacro() {
        if (!runtime.startMacro()) {
            return CommandActionResult.failure("Macro is already active.");
        }
        armExpectedClose();
        return CommandActionResult.success("Macro started.");
    }

    @Override
    public CommandActionResult pauseMacro() {
        if (!runtime.pauseMacro()) {
            return CommandActionResult.failure("Macro is not active or is already manually paused.");
        }
        armExpectedClose();
        return CommandActionResult.success("Macro paused.");
    }

    @Override
    public CommandActionResult resumeMacro() {
        if (!runtime.resumeMacro()) {
            return CommandActionResult.failure("Macro is not manually paused.");
        }
        armExpectedClose();
        return CommandActionResult.success("Macro resumed.");
    }

    @Override
    public CommandActionResult stopMacro() {
        if (!runtime.stopMacro()) {
            return CommandActionResult.failure("Macro is already stopped.");
        }
        armExpectedClose();
        return CommandActionResult.success("Macro stopped.");
    }

    @Override
    public CommandActionResult setSpawn() {
        MacroLocationConfig position = currentPosition.get();
        if (position == null) {
            return CommandActionResult.failure("Spawn was not set: no player.");
        }
        return runtime.setMacroSpawn(position)
                ? CommandActionResult.success("Macro spawn set.")
                : CommandActionResult.failure("Spawn was not saved.");
    }

    @Override
    public CommandActionResult addRewarp() {
        MacroLocationConfig position = currentPosition.get();
        if (position == null) {
            return CommandActionResult.failure("Rewarp was not added: no player.");
        }
        return runtime.addMacroRewarp(position)
                ? CommandActionResult.success("Rewarp added.")
                : CommandActionResult.failure("Rewarp was not added: nearby spawn or rewarp.");
    }

    @Override
    public CommandActionResult removeRewarp() {
        MacroLocationConfig position = currentPosition.get();
        if (position == null) {
            return CommandActionResult.failure("Rewarp was not removed: no player.");
        }
        return runtime.removeMacroRewarp(position)
                ? CommandActionResult.success("Nearest rewarp removed.")
                : CommandActionResult.failure("No rewarp within two blocks.");
    }

    @Override
    public CommandActionResult clearRewarps() {
        return runtime.clearMacroRewarps()
                ? CommandActionResult.success("Rewarps cleared.")
                : CommandActionResult.failure("Rewarps were not cleared: save failed.");
    }

    @Override
    public List<String> diagnostics() {
        MacroManager macro = runtime.core().macroManager();
        ConfigLoadResult load = runtime.configLoadResult();
        List<String> lines = new ArrayList<>();
        lines.add("Config: " + lower(load.status()) + ", schema: " + load.config().schemaVersion());
        load.backup().ifPresent(path -> lines.add("Config backup: " + path.getFileName()));
        if (!load.diagnostic().isBlank()) {
            lines.add("Config diagnostic: " + load.diagnostic());
        }
        lines.add(rotationDiagnostic(macro));
        lines.add("Rotation active: " + runtime.rotation().rotating()
                + ", paused: " + runtime.rotation().paused());
        lines.add("Held input: " + runtime.input().heldKeysText());
        return List.copyOf(lines);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private static String connectionText(MacroManager macro) {
        return macro.clientSnapshot().connection().isPresent()
                ? lower(macro.clientSnapshot().connection().get().mode())
                : "unknown";
    }

    private static String rotationDiagnostic(MacroManager macro) {
        if (!macro.clientSnapshot().player().isPresent()
                || !macro.clientSnapshot().player().get().rotation().isPresent()) {
            return "Player rotation: unknown";
        }
        dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot rotation =
                macro.clientSnapshot().player().get().rotation().get();
        return "Player rotation: yaw=" + rotation.yaw() + ", pitch=" + rotation.pitch();
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

    private static MacroLocationConfig currentMinecraftPosition() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return null;
        }
        int x = client.player.blockPosition().getX();
        int y = client.player.blockPosition().getY();
        int z = client.player.blockPosition().getZ();
        dev.hylfrd.farmhelper.runtime.snapshot.Observation<GardenPlotMap.Area> area =
                GardenPlotMap.standard().areaAt(x, z);
        return new MacroLocationConfig(x, y, z, client.player.getYRot(), client.player.getXRot(),
                area.isPresent() ? area.get().number() : -1);
    }

    @FunctionalInterface
    interface RotationStarter {
        boolean start(float yaw, float pitch, int durationMs);
    }

}
