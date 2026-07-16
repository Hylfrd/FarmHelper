package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroManagerTest {
    @Test
    void lifecycleHasOneStateAuthorityForEnvironmentPauseAndTerminalStop() {
        MacroManager manager = new MacroManager();
        ClientSnapshot observed = new ClientSnapshot(
                Observation.present(player()), Observation.unknown(),
                Observation.unknown(), Observation.absent());

        assertFalse(manager.enabled());
        assertEquals(MacroState.STOPPED, manager.state());
        assertEquals(ClientSnapshot.unknown(), manager.clientSnapshot());

        manager.start();
        manager.tick(observed, context(Observation.present(player())));
        assertEquals(MacroState.RUNNING, manager.state());
        assertEquals(1L, manager.runningTicks());
        assertEquals(observed, manager.clientSnapshot());

        manager.tick(ClientSnapshot.unknown(), context(Observation.absent()));
        assertEquals(MacroState.PAUSED, manager.state());
        assertTrue(manager.pauseCauses().contains(MacroPauseCause.ENVIRONMENT));
        assertEquals(1L, manager.runningTicks());

        manager.tick(observed, context(Observation.present(player())));
        assertEquals(MacroState.RUNNING, manager.state());
        assertEquals(2L, manager.runningTicks());

        manager.stop(MacroTerminalReason.WORLD_CHANGE);
        assertFalse(manager.enabled());
        assertEquals(MacroState.STOPPED, manager.state());
        assertEquals(0L, manager.runningTicks());
        assertEquals(MacroTerminalReason.WORLD_CHANGE,
                manager.lastTerminalReason().orElseThrow());
    }

    @Test
    void toggleStartsTheRealMacro() {
        MacroManager manager = new MacroManager();

        assertTrue(manager.toggle());
        assertEquals("s-shape-vertical", manager.activeMacroId());
        assertEquals(0L, manager.runningTicks());

        assertFalse(manager.toggle());
        assertEquals(MacroState.STOPPED, manager.state());
    }

    @Test
    void throwingStopCallbackStillCommitsStoppedState() {
        Macro throwing = new Macro() {
            @Override public String id() { return "throwing"; }
            @Override public void onStart() { }
            @Override public void onStop() { throw new IllegalStateException("stop failed"); }
        };
        MacroManager manager = new MacroManager(throwing, () -> { });
        manager.start();

        assertThrows(IllegalStateException.class, manager::stop);

        assertEquals(MacroState.STOPPED, manager.state());
        assertEquals(0L, manager.runningTicks());
        assertFalse(manager.enabled());
        assertEquals(MacroTerminalReason.MANUAL_STOP,
                manager.lastTerminalReason().orElseThrow());
    }

    @Test
    void failedStartBecomesExceptionTerminalAndInvalidatesPartialRun() {
        Macro throwing = new Macro() {
            @Override public String id() { return "throwing-start"; }
            @Override public void onStart() { throw new IllegalStateException("start failed"); }
            @Override public void onStop() { }
        };
        MacroManager manager = new MacroManager(throwing, () -> { });

        assertThrows(IllegalStateException.class, manager::start);

        assertEquals(MacroState.STOPPED, manager.state());
        assertEquals(MacroTerminalReason.EXCEPTION,
                manager.lastTerminalReason().orElseThrow());
        assertTrue(manager.generation() > 1L);
    }

    @Test
    void developmentGardenTruthIsMacroOnlyAndStatusIsLabeled() {
        MacroManager development = new MacroManager();
        development.start();
        development.tick(ClientSnapshot.unknown(), new FarmingContext(
                0L, 0L, Observation.present(player()), Observation.unknown(),
                Observation.present(true), true, ServerResponsiveness.RESPONSIVE));
        assertTrue(development.lastStatus().startsWith("[DEV WORLD] "));

        MacroManager production = new MacroManager();
        production.start();
        production.tick(ClientSnapshot.unknown(), new FarmingContext(
                0L, 0L, Observation.present(player()), Observation.unknown(),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE));
        assertFalse(production.lastStatus().contains("[DEV WORLD]"));
    }

    @Test
    void spatialCaptureDelegatesOnlyWhileLifecycleIsRunning() {
        AtomicInteger requests = new AtomicInteger();
        Macro macro = new Macro() {
            @Override public String id() { return "capture-counting"; }
            @Override public void onStart() { }
            @Override public void onStop() { }
            @Override public Optional<SpatialCaptureRequest> spatialRequest(
                    PlayerSnapshot player, long worldEpoch) {
                requests.incrementAndGet();
                return Optional.of(new SpatialCaptureRequest(
                        worldEpoch, new BoxSnapshot(0, 0, 0, 1, 1, 1),
                        Set.of(new BlockPosition(0, 0, 0))));
            }
        };
        MacroManager manager = new MacroManager(macro, () -> { });

        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        assertEquals(0, requests.get());
        manager.start();
        long generation = manager.generation();
        assertTrue(manager.spatialRequest(player(), 1L).isPresent());
        assertEquals(1, requests.get());

        manager.manualPause();
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        manager.manualResume();
        assertTrue(manager.spatialRequest(player(), 1L).isPresent());

        manager.observeScreen(true);
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        manager.observeScreen(false);
        assertTrue(manager.spatialRequest(player(), 1L).isPresent());

        manager.tick(ClientSnapshot.unknown(), context(Observation.absent()));
        assertTrue(manager.pauseCauses().contains(MacroPauseCause.ENVIRONMENT));
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        ClientSnapshot observed = new ClientSnapshot(
                Observation.present(player()), Observation.unknown(),
                Observation.unknown(), Observation.absent());
        manager.tick(observed, context(Observation.present(player())));
        assertTrue(manager.spatialRequest(player(), 1L).isPresent());

        FeatureSuspension first = manager.suspendForFeature("first");
        FeatureSuspension second = manager.suspendForFeature("second");
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        first.close();
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        second.close();
        assertTrue(manager.spatialRequest(player(), 1L).isPresent());
        assertEquals(generation, manager.generation());

        manager.stop(MacroTerminalReason.MANUAL_STOP);
        assertTrue(manager.spatialRequest(player(), 1L).isEmpty());
        assertEquals(5, requests.get());
    }

    @Test
    void resumedSpatialCaptureRestoresSameGenerationRequests() {
        MacroManager manager = new MacroManager();
        manager.start();
        long generation = manager.generation();
        assertTrue(manager.spatialRequest(player(), 4L).isPresent());

        manager.manualPause();
        assertTrue(manager.spatialRequest(player(), 4L).isEmpty());
        manager.manualResume();
        assertTrue(manager.spatialRequest(player(), 4L).isPresent());

        assertEquals(generation, manager.generation());
    }

    @Test
    void everyPersistedModeStartsItsExpectedImplementationAcrossStoppedSwitches() {
        AtomicInteger acquisitions = new AtomicInteger();
        MacroManager manager = new MacroManager(acquisitions::incrementAndGet);
        Set<String> resolvedIds = new HashSet<>();
        String stoppedActiveId = "s-shape-vertical";
        long previousGeneration = manager.generation();

        for (MacroMode mode : MacroMode.values()) {
            manager.updateSettings(settings -> settings.macroMode(mode));

            assertEquals(stoppedActiveId, manager.activeMacroId(),
                    "a stopped settings edit must not masquerade as an active instance switch");
            assertEquals(mode, manager.configuredMode());
            assertTrue(manager.configuredModeImplemented());

            manager.start();
            String expectedId = expectedMacroId(mode);
            assertEquals(expectedId, manager.activeMacroId());
            assertEquals(MacroState.RUNNING, manager.state());
            assertTrue(manager.generation() > previousGeneration);
            assertThrows(IllegalStateException.class,
                    () -> manager.updateSettings(settings -> settings.macroMode(MacroMode.VERTICAL_NORMAL)));
            resolvedIds.add(manager.activeMacroId());
            long runningGeneration = manager.generation();

            manager.stop();

            assertEquals(MacroState.STOPPED, manager.state());
            assertEquals(MacroTerminalReason.MANUAL_STOP,
                    manager.lastTerminalReason().orElseThrow());
            assertTrue(manager.generation() > runningGeneration);
            stoppedActiveId = expectedId;
            previousGeneration = manager.generation();
        }

        assertEquals(MacroMode.values().length, acquisitions.get());
        assertEquals(Set.of(
                "s-shape-vertical",
                "s-shape-melon-pumpkin-default",
                "s-shape-sugarcane",
                "s-shape-cocoa-beans",
                "s-shape-mushroom",
                "s-shape-mushroom-rotate",
                "s-shape-mushroom-sds",
                "circular-crop"), resolvedIds);
    }

    @Test
    void everyFamilyKeepsOneGenerationAcrossNestedPauseAndTerminallyInvalidatesIt() {
        for (MacroMode mode : List.of(
                MacroMode.VERTICAL_NORMAL,
                MacroMode.MELON_PUMPKIN_DEFAULT,
                MacroMode.SUGAR_CANE,
                MacroMode.COCOA,
                MacroMode.MUSHROOM,
                MacroMode.MUSHROOM_ROTATE,
                MacroMode.MUSHROOM_SDS,
                MacroMode.CIRCULAR)) {
            MacroManager manager = new MacroManager();
            manager.updateSettings(settings -> settings.macroMode(mode));
            manager.start();
            long runningGeneration = manager.generation();

            FeatureSuspension first = manager.suspendForFeature("first");
            FeatureSuspension second = manager.suspendForFeature("second");
            manager.observeScreen(true);
            assertEquals(MacroState.PAUSED, manager.state());
            assertEquals(Set.of(MacroPauseCause.FEATURE, MacroPauseCause.SCREEN_OPEN),
                    manager.pauseCauses());
            first.close();
            second.close();
            assertEquals(MacroState.PAUSED, manager.state());
            assertEquals(Set.of(MacroPauseCause.SCREEN_OPEN), manager.pauseCauses());
            assertEquals(runningGeneration, manager.generation());

            manager.observeScreen(false);
            assertEquals(MacroState.RUNNING, manager.state());
            assertEquals(runningGeneration, manager.generation());
            assertEquals(expectedMacroId(mode), manager.activeMacroId());

            manager.stop(MacroTerminalReason.WORLD_CHANGE);
            assertEquals(MacroState.STOPPED, manager.state());
            assertEquals(MacroTerminalReason.WORLD_CHANGE,
                    manager.lastTerminalReason().orElseThrow());
            assertTrue(manager.generation() > runningGeneration);
            assertTrue(manager.pauseCauses().isEmpty());
        }
    }

    @Test
    void modeThreeResolvesItsLeafAndActiveRunCannotBeReplacedBySecondStart() {
        MacroManager manager = new MacroManager();
        manager.settings().macroMode(MacroMode.MELON_PUMPKIN_DEFAULT);

        manager.start();

        assertEquals("s-shape-melon-pumpkin-default", manager.activeMacroId());
        long generation = manager.generation();
        assertThrows(IllegalStateException.class,
                () -> manager.settings().macroMode(MacroMode.VERTICAL_NORMAL));
        assertThrows(IllegalStateException.class, manager::start);
        assertEquals("s-shape-melon-pumpkin-default", manager.activeMacroId());
        assertEquals(generation, manager.generation());
    }

    private static String expectedMacroId(MacroMode mode) {
        return switch (mode.family()) {
            case VERTICAL_S_SHAPE -> "s-shape-vertical";
            case MELON_PUMPKIN_DEFAULT -> "s-shape-melon-pumpkin-default";
            case SUGAR_CANE -> "s-shape-sugarcane";
            case COCOA -> "s-shape-cocoa-beans";
            case MUSHROOM -> "s-shape-mushroom";
            case MUSHROOM_ROTATE -> "s-shape-mushroom-rotate";
            case MUSHROOM_SDS -> "s-shape-mushroom-sds";
            case CIRCULAR -> "circular-crop";
        };
    }

    private static FarmingContext context(Observation<PlayerSnapshot> player) {
        return new FarmingContext(0L, 0L, player, Observation.unknown(),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE);
    }

    private static PlayerSnapshot player() {
        return new PlayerSnapshot(
                Observation.present(new PositionSnapshot(1.0D, 2.0D, 3.0D)),
                Observation.present(new MotionSnapshot(0.0D, 0.0D, 0.0D)),
                Observation.present(new RotationSnapshot(0.0F, 2.8F)),
                Observation.unknown(), Observation.unknown());
    }
}
