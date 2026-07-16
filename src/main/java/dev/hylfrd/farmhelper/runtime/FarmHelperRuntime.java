package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.control.expectation.ExpectedActionLedger;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.ServerTimeTracker;
import dev.hylfrd.farmhelper.navigation.NavigationController;
import dev.hylfrd.farmhelper.navigation.NavigationTaskOwner;
import dev.hylfrd.farmhelper.navigation.NavigationTerminalCleanup;
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
    private final ExpectedActionLedger expectedActions;
    private final NavigationController navigationController;
    private final MonotonicClock clock;
    private final ServerTimeTracker serverTimeTracker;
    private final GameStateParser gameStateParser;
    private GameStateParseResult currentGameState;

    public FarmHelperRuntime() {
        this(new FarmHelperConfig(), () -> { });
    }

    public FarmHelperRuntime(FarmHelperConfig config) {
        this(config, () -> { });
    }

    public FarmHelperRuntime(FarmHelperConfig config, Runnable acquisitionGuard) {
        this(config, SystemMonotonicClock.INSTANCE, acquisitionGuard, Runnable::run);
    }

    public FarmHelperRuntime(FarmHelperConfig config, ClientOwnershipFence ownershipFence) {
        this(config, ownershipFence, new NavigationTerminalCleanup[0]);
    }

    public FarmHelperRuntime(
            FarmHelperConfig config,
            ClientOwnershipFence ownershipFence,
            NavigationTerminalCleanup... navigationCleanups
    ) {
        this(config, SystemMonotonicClock.INSTANCE,
                ownershipFence::requireAcquisitionAllowed,
                ownershipFence::runTaskCallback,
                navigationCleanups);
    }

    private FarmHelperRuntime(
            FarmHelperConfig config,
            MonotonicClock clock,
            Runnable acquisitionGuard,
            java.util.function.Consumer<Runnable> callbackRunner,
            NavigationTerminalCleanup... navigationCleanups) {
        this(config, new MacroManager(clock, acquisitionGuard), clock, acquisitionGuard,
                callbackRunner, navigationCleanups);
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
        this(config, macroManager, clock, acquisitionGuard, callbackRunner,
                new NavigationTerminalCleanup[0]);
    }

    FarmHelperRuntime(
            FarmHelperConfig config,
            MacroManager macroManager,
            MonotonicClock clock,
            Runnable acquisitionGuard,
            java.util.function.Consumer<Runnable> callbackRunner,
            NavigationTerminalCleanup... navigationCleanups) {
        this.config = config;
        this.macroManager = macroManager;
        this.clock = Objects.requireNonNull(clock, "clock");
        serverTimeTracker = new ServerTimeTracker();
        taskQueue = new ClientTaskQueue(
                clock,
                Objects.requireNonNull(acquisitionGuard, "acquisitionGuard"),
                Objects.requireNonNull(callbackRunner, "callbackRunner"));
        expectedActions = new ExpectedActionLedger(clock, acquisitionGuard);
        Objects.requireNonNull(navigationCleanups, "navigationCleanups");
        NavigationTerminalCleanup[] cleanups = new NavigationTerminalCleanup[
                navigationCleanups.length + 2];
        System.arraycopy(navigationCleanups, 0, cleanups, 0, navigationCleanups.length);
        cleanups[navigationCleanups.length] =
                (ticket, result) -> taskQueue.cancel(NavigationTaskOwner.from(ticket));
        cleanups[navigationCleanups.length + 1] = (ticket, result) ->
                expectedActions.clear(ticket.owner(), ticket.generation());
        navigationController = new NavigationController(acquisitionGuard, cleanups);
        gameStateParser = new GameStateParser();
        synchronizeMacroSettings();
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

    public ExpectedActionLedger expectedActions() {
        return expectedActions;
    }

    public NavigationController navigationController() {
        return navigationController;
    }

    public void serverJoined() {
        serverTimeTracker.joined(clock.nowNanos());
    }

    public void receivedServerTimePacket() {
        serverTimeTracker.receivedTimePacket(clock.nowNanos());
    }

    public ServerResponsiveness serverResponsiveness(boolean connectionReady) {
        return serverTimeTracker.observe(clock.nowNanos(), connectionReady);
    }

    public void resetServerTimeTracker() {
        serverTimeTracker.reset();
    }

    public long nowNanos() {
        return clock.nowNanos();
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

    public void synchronizeMacroSettings() {
        dev.hylfrd.farmhelper.macro.MacroMode requestedMode =
                dev.hylfrd.farmhelper.macro.MacroMode
                        .fromCode(config.macroMode()).orElseThrow();
        macroManager.updateSettings(settings -> settings.replace(
                requestedMode,
                config.macroSpawn().map(spawn ->
                        new dev.hylfrd.farmhelper.macro.MacroSpawnPose(
                                new dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition(
                                        spawn.x(), spawn.y(), spawn.z()),
                                spawn.yaw(), spawn.pitch(), spawn.plot())),
                config.macroRewarps().stream().map(rewarp ->
                        new dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition(
                                rewarp.x(), rewarp.y(), rewarp.z())).toList(),
                config.alwaysHoldW(), config.holdLeftClickWhenChangingRow(),
                config.rotateAfterWarped(), config.rotateAfterDrop(), config.dontFixAfterWarping(),
                config.customPitch(), config.customPitchLevel(), config.customYaw(),
                config.customYawLevel()));
    }
}
