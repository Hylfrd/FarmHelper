package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.macro.impl.StandbyMacro;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;

import java.util.List;
import java.util.Objects;

public final class MacroManager {
    private final Macro activeMacro;
    private final Runnable acquisitionGuard;
    private MacroState state = MacroState.STOPPED;
    private PauseReason pauseReason = PauseReason.NONE;
    private WorldMode worldMode = WorldMode.NONE;
    private PlayerSnapshot playerSnapshot = PlayerSnapshot.unknown();
    private ClientSnapshot clientSnapshot = ClientSnapshot.unknown();
    private long runningTicks;

    public MacroManager() {
        this(new StandbyMacro(), () -> { });
    }

    public MacroManager(Runnable acquisitionGuard) {
        this(new StandbyMacro(), acquisitionGuard);
    }

    MacroManager(Macro activeMacro, Runnable acquisitionGuard) {
        this.activeMacro = Objects.requireNonNull(activeMacro, "activeMacro");
        this.acquisitionGuard = Objects.requireNonNull(acquisitionGuard, "acquisitionGuard");
    }

    public boolean enabled() {
        return state != MacroState.STOPPED;
    }

    public MacroState state() {
        return state;
    }

    public String activeMacroId() {
        return activeMacro.id();
    }

    public long runningTicks() {
        return runningTicks;
    }

    public PauseReason pauseReason() {
        return pauseReason;
    }

    public WorldMode worldMode() {
        return worldMode;
    }

    public PlayerSnapshot playerSnapshot() {
        return playerSnapshot;
    }

    public ClientSnapshot clientSnapshot() {
        return clientSnapshot;
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
        acquisitionGuard.run();
        runningTicks = 0L;
        state = MacroState.RUNNING;
        try {
            activeMacro.onStart();
        } catch (RuntimeException | Error failure) {
            state = MacroState.STOPPED;
            pauseReason = PauseReason.NONE;
            runningTicks = 0L;
            throw failure;
        }
    }

    public void stop() {
        try {
            if (state != MacroState.STOPPED) {
                activeMacro.onStop();
            }
        } finally {
            state = MacroState.STOPPED;
            pauseReason = PauseReason.NONE;
            runningTicks = 0L;
        }
    }

    public void tick(MacroContext context) {
        tick(legacyClientSnapshot(context), context);
    }

    public void tick(ClientSnapshot clientSnapshot, MacroContext context) {
        this.clientSnapshot = Objects.requireNonNull(clientSnapshot, "clientSnapshot");
        Objects.requireNonNull(context, "context");
        worldMode = context.worldMode();
        playerSnapshot = context.player();
        if (state == MacroState.STOPPED) {
            return;
        }
        if (!context.playerReady() || context.screenOpen()) {
            state = MacroState.PAUSED;
            pauseReason = context.pauseReason();
            return;
        }
        state = MacroState.RUNNING;
        pauseReason = PauseReason.NONE;
        runningTicks++;
        activeMacro.tick(context);
    }

    private static ClientSnapshot legacyClientSnapshot(MacroContext context) {
        Objects.requireNonNull(context, "context");
        Observation<dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot> player = switch (context.player().state()) {
            case PRESENT -> Observation.present(new dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot(
                    Observation.present(new PositionSnapshot(
                            context.player().x(),
                            context.player().y(),
                            context.player().z())),
                    Observation.unknown(),
                    Observation.present(new RotationSnapshot(
                            context.player().yaw(),
                            context.player().pitch())),
                    Observation.unknown(),
                    Observation.<List<dev.hylfrd.farmhelper.runtime.snapshot.StatusEffectSummary>>unknown()));
            case ABSENT -> Observation.absent();
            case UNKNOWN -> Observation.unknown();
        };
        Observation<WorldSnapshot> world = switch (context.worldMode()) {
            case SINGLEPLAYER, MULTIPLAYER -> Observation.present(new WorldSnapshot(Observation.unknown()));
            case NONE -> context.pauseReason() == PauseReason.NO_WORLD
                    ? Observation.absent()
                    : Observation.unknown();
        };
        Observation<ConnectionSnapshot> connection = switch (context.worldMode()) {
            case SINGLEPLAYER -> Observation.present(ConnectionSnapshot.singleplayer());
            case MULTIPLAYER -> Observation.present(ConnectionSnapshot.multiplayer());
            case NONE -> context.pauseReason() == PauseReason.NO_WORLD
                    ? Observation.absent()
                    : Observation.unknown();
        };
        Observation<ScreenSnapshot> screen = context.screenOpen()
                ? Observation.present(ScreenSnapshot.unknownDetails())
                : Observation.absent();
        return new ClientSnapshot(player, world, connection, screen);
    }
}
