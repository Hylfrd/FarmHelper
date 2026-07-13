package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroManagerTest {
    @Test
    void followsTheExistingStartPauseResumeStopLifecycle() {
        MacroManager manager = new MacroManager();
        PlayerSnapshot snapshot = new PlayerSnapshot(1.0D, 2.0D, 3.0D, 45.0F, 10.0F);

        assertFalse(manager.enabled());
        assertEquals(MacroState.STOPPED, manager.state());

        manager.start();
        manager.tick(new MacroContext(true, false, PauseReason.NONE, WorldMode.SINGLEPLAYER, snapshot));

        assertTrue(manager.enabled());
        assertEquals(MacroState.RUNNING, manager.state());
        assertEquals(1L, manager.runningTicks());
        assertEquals(snapshot, manager.playerSnapshot());

        manager.tick(new MacroContext(false, false, PauseReason.NO_PLAYER, WorldMode.NONE, PlayerSnapshot.empty()));

        assertEquals(MacroState.PAUSED, manager.state());
        assertEquals(PauseReason.NO_PLAYER, manager.pauseReason());
        assertEquals(1L, manager.runningTicks());

        manager.tick(new MacroContext(true, false, PauseReason.NONE, WorldMode.MULTIPLAYER, snapshot));

        assertEquals(MacroState.RUNNING, manager.state());
        assertEquals(PauseReason.NONE, manager.pauseReason());
        assertEquals(2L, manager.runningTicks());

        manager.stop();

        assertFalse(manager.enabled());
        assertEquals(MacroState.STOPPED, manager.state());
        assertEquals(PauseReason.NONE, manager.pauseReason());
        assertEquals(0L, manager.runningTicks());
    }

    @Test
    void toggleDoesNotStartAnyFarmingBehavior() {
        MacroManager manager = new MacroManager();

        assertTrue(manager.toggle());
        assertEquals("standby", manager.activeMacroId());
        assertEquals(0L, manager.runningTicks());

        assertFalse(manager.toggle());
        assertEquals(MacroState.STOPPED, manager.state());
    }
}
