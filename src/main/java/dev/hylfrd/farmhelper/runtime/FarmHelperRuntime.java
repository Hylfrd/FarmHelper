package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParser;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.lifecycle.ClientOwnershipFence;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.time.ClientTaskQueue;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.SystemMonotonicClock;

import java.util.Objects;
import java.util.Optional;

/** Owns the version-independent mutable services for one FarmHelper runtime. */
public final class FarmHelperRuntime {
    private final FarmHelperConfig config;
    private final MacroManager macroManager;
    private final ClientTaskQueue taskQueue;
    private final GameStateParser gameStateParser;
    private GameStateParseResult currentGameState;

    public FarmHelperRuntime() {
        this(new FarmHelperConfig(), () -> { });
    }

    public FarmHelperRuntime(FarmHelperConfig config) {
        this(config, () -> { });
    }

    public FarmHelperRuntime(FarmHelperConfig config, Runnable acquisitionGuard) {
        this(config, new MacroManager(acquisitionGuard), SystemMonotonicClock.INSTANCE,
                acquisitionGuard, Runnable::run);
    }

    public FarmHelperRuntime(FarmHelperConfig config, ClientOwnershipFence ownershipFence) {
        this(config,
                new MacroManager(Objects.requireNonNull(
                        ownershipFence, "ownershipFence")::requireAcquisitionAllowed),
                SystemMonotonicClock.INSTANCE,
                ownershipFence::requireAcquisitionAllowed,
                ownershipFence::runTaskCallback);
    }

    FarmHelperRuntime(FarmHelperConfig config, MacroManager macroManager) {
        this(config, macroManager, SystemMonotonicClock.INSTANCE, () -> { }, Runnable::run);
    }

    FarmHelperRuntime(FarmHelperConfig config, MacroManager macroManager, MonotonicClock clock) {
        this(config, macroManager, clock, () -> { }, Runnable::run);
    }

    FarmHelperRuntime(
            FarmHelperConfig config,
            MacroManager macroManager,
            MonotonicClock clock,
            Runnable acquisitionGuard,
            java.util.function.Consumer<Runnable> callbackRunner) {
        this.config = config;
        this.macroManager = macroManager;
        taskQueue = new ClientTaskQueue(
                Objects.requireNonNull(clock, "clock"),
                Objects.requireNonNull(acquisitionGuard, "acquisitionGuard"),
                Objects.requireNonNull(callbackRunner, "callbackRunner"));
        gameStateParser = new GameStateParser();
    }

    public FarmHelperConfig config() {
        return config;
    }

    public MacroManager macroManager() {
        return macroManager;
    }

    public ClientTaskQueue taskQueue() {
        return taskQueue;
    }

    /** Parses and publishes the current tick before any due stateful task may run. */
    public GameStateParseResult parseGameState(ClientSnapshot client, RawGameTextSnapshot raw) {
        currentGameState = gameStateParser.parse(
                Objects.requireNonNull(client, "client"), Objects.requireNonNull(raw, "raw"));
        return currentGameState;
    }

    public Optional<GameStateParseResult> currentGameState() {
        return Optional.ofNullable(currentGameState);
    }

    public void invalidateGameState() {
        currentGameState = null;
    }
}
