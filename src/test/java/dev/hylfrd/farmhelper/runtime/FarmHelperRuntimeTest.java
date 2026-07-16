package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.MacroLocationConfig;
import dev.hylfrd.farmhelper.control.expectation.ExpectedMotion;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationHandle;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.navigation.NavigationTaskOwner;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperRuntimeTest {
    @Test
    void eachRuntimeOwnsIndependentMutableServices() {
        FarmHelperRuntime first = new FarmHelperRuntime();
        FarmHelperRuntime second = new FarmHelperRuntime();

        first.config().setTargetYaw(45.0F);
        first.macroManager().start();

        assertNotSame(first.config(), second.config());
        assertNotSame(first.macroManager(), second.macroManager());
        assertNotSame(first.taskQueue(), second.taskQueue());
        assertNotSame(first.navigationController(), second.navigationController());
        assertNotSame(first.expectedActions(), second.expectedActions());
        assertEquals(45.0F, first.config().targetYaw());
        assertEquals(0.0F, second.config().targetYaw());
        assertTrue(first.macroManager().enabled());
        assertFalse(second.macroManager().enabled());
    }

    @Test
    void navigationTerminalClearsOnlyItsExactDerivedTaskAndExpectationGeneration() {
        MutableClock clock = new MutableClock();
        FarmHelperRuntime runtime = new FarmHelperRuntime(
                new FarmHelperConfig(), new MacroManager(clock, () -> { }), clock);
        ControlOwner owner = new ControlOwner("runtime-navigation");
        NavigationHandle handle = runtime.navigationController().start(
                new NavigationRequest(owner, 3L, new NavigationGoal(1, 70, 1),
                        NavigationOptions.fly()),
                new NavigationStartObservation(
                        Observation.present(new WorldSnapshot(3L, Observation.unknown())),
                        Observation.present(new PlayerSnapshot(
                                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                                Observation.unknown(), Observation.unknown())),
                        Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent()));
        runtime.taskQueue().schedule(NavigationTaskOwner.from(handle.ticket()), 1_000L, () -> { });
        runtime.expectedActions().publish(owner, handle.ticket().generation(), 3L, 0L, 1_000L,
                new ExpectedMotion(new MotionSnapshot(1, 0, 0), 0.1D));
        runtime.expectedActions().publish(owner, handle.ticket().generation() + 1L, 3L,
                0L, 1_000L, new ExpectedMotion(new MotionSnapshot(0, 0, 1), 0.1D));

        assertTrue(handle.cancel());

        assertEquals(0, runtime.taskQueue().pendingTaskCount());
        assertEquals(1, runtime.expectedActions().snapshot().actions().size());
        assertEquals(handle.ticket().generation() + 1L,
                runtime.expectedActions().snapshot().actions().getFirst().generation());
    }

    @Test
    void sessionBoundariesResetLastPacketAndGrace() {
        MutableClock clock = new MutableClock();
        FarmHelperRuntime runtime = new FarmHelperRuntime(
                new FarmHelperConfig(), new MacroManager(clock, () -> { }), clock);
        runtime.serverJoined();
        clock.now = TimeUnit.SECONDS.toNanos(5L);
        runtime.receivedServerTimePacket();
        clock.now += TimeUnit.MILLISECONDS.toNanos(1_300L);
        assertEquals(ServerResponsiveness.RESPONSIVE, runtime.serverResponsiveness(true));

        runtime.resetServerTimeTracker();
        assertEquals(ServerResponsiveness.UNKNOWN, runtime.serverResponsiveness(true));
        runtime.serverJoined();
        clock.now += TimeUnit.MILLISECONDS.toNanos(4_999L);
        assertEquals(ServerResponsiveness.RESPONSIVE, runtime.serverResponsiveness(true));
    }

    @Test
    void macroSettingsSynchronizationAtomicallyClearsAbsentLocations() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setMacroMode(5);
        config.setAlwaysHoldW(true);
        config.setHoldLeftClickWhenChangingRow(false);
        config.setMacroSpawn(new MacroLocationConfig(0, 70, 0, 91.5F, -12.25F, 7));
        config.addMacroRewarp(new MacroLocationConfig(3, 70, 0, 0F, 0F, 0));
        FarmHelperRuntime runtime = new FarmHelperRuntime(config);
        assertTrue(runtime.macroManager().settings().spawn().isPresent());
        assertEquals(91.5F, runtime.macroManager().settings().spawn().orElseThrow().yaw());
        assertEquals(-12.25F, runtime.macroManager().settings().spawn().orElseThrow().pitch());
        assertEquals(7, runtime.macroManager().settings().spawn().orElseThrow().plot());
        assertEquals(1, runtime.macroManager().settings().rewarps().size());
        assertTrue(runtime.macroManager().settings().alwaysHoldW());
        assertFalse(runtime.macroManager().settings().holdLeftClickWhenChangingRow());

        config.reset();
        runtime.synchronizeMacroSettings();

        assertTrue(runtime.macroManager().settings().spawn().isEmpty());
        assertTrue(runtime.macroManager().settings().rewarps().isEmpty());
        assertEquals(0, runtime.macroManager().settings().mode().code());
        assertFalse(runtime.macroManager().settings().alwaysHoldW());
        assertTrue(runtime.macroManager().settings().holdLeftClickWhenChangingRow());
    }

    private static final class MutableClock implements MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }
    }
}
