package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.macro.impl.StandbyMacro;

public final class MacroManager {
    private final Macro activeMacro = new StandbyMacro();
    private MacroState state = MacroState.STOPPED;
    private PauseReason pauseReason = PauseReason.NONE;
    private WorldMode worldMode = WorldMode.NONE;
    private PlayerSnapshot playerSnapshot = PlayerSnapshot.empty();
    private long runningTicks;

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

    public boolean toggle() {
        if (enabled()) {
            stop();
            return false;
        }
        start();
        return true;
    }

    public void start() {
        runningTicks = 0L;
        state = MacroState.RUNNING;
        activeMacro.onStart();
    }

    public void stop() {
        if (state != MacroState.STOPPED) {
            activeMacro.onStop();
        }
        state = MacroState.STOPPED;
        pauseReason = PauseReason.NONE;
        runningTicks = 0L;
    }

    public void tick(MacroContext context) {
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
}
