package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.macro.impl.SShapeVerticalCropMacro;
import dev.hylfrd.farmhelper.macro.impl.SShapeMelonPumpkinDefaultMacro;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.SystemMonotonicClock;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class MacroManager implements MacroLifecycleTarget {
    private static final Runnable NOOP_OBSERVER = () -> { };
    private Macro activeMacro;
    private final Macro fixedMacro;
    private final MacroRegistry registry;
    private final Runnable acquisitionGuard;
    private final MacroLifecycle lifecycle;
    private final MacroSettings settings;
    private ClientSnapshot clientSnapshot = ClientSnapshot.unknown();
    private long runningTicks;
    private MacroTerminalReason lastTerminalReason;
    private String lastStatus = "stopped";
    private Runnable pauseObserver = NOOP_OBSERVER;

    public MacroManager() {
        this(SystemMonotonicClock.INSTANCE, () -> { });
    }

    public MacroManager(Runnable acquisitionGuard) {
        this(SystemMonotonicClock.INSTANCE, acquisitionGuard);
    }

    MacroManager(Macro activeMacro, Runnable acquisitionGuard) {
        this(activeMacro, SystemMonotonicClock.INSTANCE, acquisitionGuard);
    }

    public MacroManager(MonotonicClock clock, Runnable acquisitionGuard) {
        this(new MacroSettings(), clock, acquisitionGuard);
    }

    private MacroManager(MacroSettings settings, MonotonicClock clock, Runnable acquisitionGuard) {
        this(null, settings, clock, acquisitionGuard);
    }

    MacroManager(Macro activeMacro, MonotonicClock clock, Runnable acquisitionGuard) {
        this(activeMacro, new MacroSettings(), clock, acquisitionGuard);
    }

    private MacroManager(
            Macro activeMacro,
            MacroSettings settings,
            MonotonicClock clock,
            Runnable acquisitionGuard
    ) {
        this.fixedMacro = activeMacro;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.acquisitionGuard = Objects.requireNonNull(acquisitionGuard, "acquisitionGuard");
        registry = activeMacro == null
                ? new MacroRegistry(Map.of(
                        MacroFamily.VERTICAL_S_SHAPE,
                        () -> new SShapeVerticalCropMacro(settings.snapshot(),
                                () -> ThreadLocalRandom.current().nextDouble(),
                                () -> ThreadLocalRandom.current().nextDouble()),
                        MacroFamily.MELON_PUMPKIN_DEFAULT,
                        () -> new SShapeMelonPumpkinDefaultMacro(settings.snapshot(),
                                () -> ThreadLocalRandom.current().nextDouble(),
                                () -> ThreadLocalRandom.current().nextDouble())))
                : new MacroRegistry(Map.of());
        this.activeMacro = activeMacro == null
                ? registry.create(settings.macroMode()).orElseThrow()
                : activeMacro;
        lifecycle = new MacroLifecycle(this, Objects.requireNonNull(clock, "clock"));
    }

    public boolean enabled() {
        return lifecycle.state() != MacroState.STOPPED;
    }

    public MacroState state() {
        return lifecycle.state();
    }

    public String activeMacroId() {
        return activeMacro.id();
    }

    public MacroMode configuredMode() {
        return settings.macroMode();
    }

    public boolean configuredModeImplemented() {
        return fixedMacro != null || registry.implemented(settings.macroMode());
    }

    public long runningTicks() {
        return runningTicks;
    }

    public long generation() {
        return lifecycle.generation();
    }

    public Set<MacroPauseCause> pauseCauses() {
        return lifecycle.pauseCauses();
    }

    public ClientSnapshot clientSnapshot() {
        return clientSnapshot;
    }

    public Optional<MacroTerminalReason> lastTerminalReason() {
        return Optional.ofNullable(lastTerminalReason);
    }

    public MacroSettings settings() {
        return settings;
    }

    /** The sole production mutation boundary; active runs own an immutable settings snapshot. */
    public void updateSettings(Consumer<MacroSettings> update) {
        Objects.requireNonNull(update, "update");
        if (enabled()) {
            throw new IllegalStateException("cannot change macro settings during an active run");
        }
        update.accept(settings);
    }

    public String lastStatus() {
        return lastStatus;
    }

    public void installPauseObserver(Runnable observer) {
        Objects.requireNonNull(observer, "observer");
        if (pauseObserver != NOOP_OBSERVER && pauseObserver != observer) {
            throw new IllegalStateException("macro pause observer is already installed");
        }
        pauseObserver = observer;
    }

    public Optional<SpatialCaptureRequest> spatialRequest(
            dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot player,
            long worldEpoch
    ) {
        if (!lifecycle.running()) {
            return Optional.empty();
        }
        return activeMacro.spatialRequest(player, worldEpoch);
    }

    public boolean toggle() {
        if (enabled()) {
            stop();
            return false;
        }
        start();
        return true;
    }

    public void start() {
        if (enabled()) {
            throw new IllegalStateException("macro lifecycle is already active");
        }
        Macro next = fixedMacro != null
                ? fixedMacro
                : registry.create(settings.macroMode()).orElseThrow(() ->
                        new IllegalStateException("Macro mode " + settings.macroMode().code()
                                + " is recognized but not implemented"));
        acquisitionGuard.run();
        settings.freeze();
        runningTicks = 0L;
        activeMacro = next;
        try {
            lifecycle.start();
        } catch (RuntimeException | Error failure) {
            settings.thaw();
            lastTerminalReason = MacroTerminalReason.EXCEPTION;
            runningTicks = 0L;
            throw failure;
        }
    }

    public void stop() {
        stop(MacroTerminalReason.MANUAL_STOP);
    }

    public void stop(MacroTerminalReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!enabled()) {
            if (reason == MacroTerminalReason.DISCONNECT
                    && (lastTerminalReason == MacroTerminalReason.WORLD_CHANGE
                    || lastTerminalReason == MacroTerminalReason.CONNECTION_LOST)) {
                lastTerminalReason = MacroTerminalReason.DISCONNECT;
                lastStatus = "stopped:disconnect";
            }
            return;
        }
        lifecycle.stop(reason);
    }

    public void manualPause() {
        lifecycle.manualPause();
    }

    public void manualResume() {
        lifecycle.manualResume();
    }

    public FeatureSuspension suspendForFeature(String owner) {
        return lifecycle.suspendForFeature(owner);
    }

    public void observeScreen(boolean open) {
        if (open) {
            lifecycle.screenOpen();
        } else {
            lifecycle.screenClosed();
        }
    }

    public MacroDecision tick(ClientSnapshot clientSnapshot, FarmingContext context) {
        this.clientSnapshot = Objects.requireNonNull(clientSnapshot, "clientSnapshot");
        Objects.requireNonNull(context, "context");
        if (lifecycle.state() == MacroState.STOPPED) {
            return MacroDecision.idle("stopped");
        }
        if (!context.player().isPresent()) {
            lifecycle.environmentUnavailable();
        } else {
            lifecycle.environmentAvailable();
        }
        if (!lifecycle.running()) {
            return MacroDecision.idle("paused");
        }
        runningTicks++;
        MacroDecision decision = activeMacro.tick(context);
        lastStatus = (context.developmentGarden() ? "[DEV WORLD] " : "") + decision.status();
        return decision;
    }

    @Override
    public void start(long generation, long nowNanos) {
        lastTerminalReason = null;
        lastStatus = "starting";
        activeMacro.onStart(nowNanos);
    }

    @Override
    public void pause(long generation, long nowNanos, Set<MacroPauseCause> causes) {
        activeMacro.onPause(causes, nowNanos);
        pauseObserver.run();
    }

    @Override
    public void resume(long generation, long nowNanos) {
        activeMacro.onResume(nowNanos);
    }

    @Override
    public void stop(long generation, MacroTerminalReason reason) {
        lastTerminalReason = reason;
        try {
            activeMacro.onStop(reason);
        } finally {
            runningTicks = 0L;
            lastStatus = "stopped:" + reason.name().toLowerCase();
            settings.thaw();
        }
    }
}
