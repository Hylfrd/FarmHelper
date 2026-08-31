package dev.hylfrd.farmhelper.feature.lifecycle;

import dev.hylfrd.farmhelper.failsafe.FailsafeArbitrator;
import dev.hylfrd.farmhelper.failsafe.FailsafeCandidate;
import dev.hylfrd.farmhelper.failsafe.FailsafeType;
import dev.hylfrd.farmhelper.macro.MacroLifecycle;
import dev.hylfrd.farmhelper.macro.MacroLifecycleTarget;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureLifecycleTest {
    private static final long WORLD_EPOCH = 17L;

    @Test
    void registrationRejectsDuplicateIdentityAndPreservesDeclaredOrder() {
        Harness harness = new Harness();
        RecordingFeature shared = new RecordingFeature("shared", harness.events);
        FeatureRegistration alpha = registration(
                "alpha", policy(false, false, false, false), shared);
        FeatureRegistration beta = registration(
                "beta", policy(false, false, false, false),
                new RecordingFeature("beta", harness.events));

        FeatureLifecycle lifecycle = harness.lifecycle(alpha, beta);

        assertEquals(List.of(id("alpha"), id("beta")), lifecycle.registeredFeatures());
        assertThrows(UnsupportedOperationException.class,
                () -> lifecycle.registeredFeatures().add(id("other")));
        assertThrows(IllegalArgumentException.class, () -> harness.lifecycle(
                alpha,
                registration("alpha", policy(false, false, false, false),
                        new RecordingFeature("duplicate", harness.events))));
        assertThrows(IllegalArgumentException.class, () -> harness.lifecycle(
                alpha,
                registration("other", policy(false, false, false, false), shared)));
        assertThrows(IllegalArgumentException.class,
                () -> new FeaturePolicy(false, true, true,
                        FeatureFailsafePolicy.ALLOW_CHECKS));
        assertEquals(id("trimmed"), new FeatureId("  trimmed  "));
        assertThrows(IllegalArgumentException.class, () -> new FeatureId("  "));
    }

    @Test
    void macroStartSnapshotsEligibilityAndUsesRegistrationOrder() {
        Harness harness = new Harness();
        AtomicBoolean secondEnabled = new AtomicBoolean(true);
        AtomicBoolean thirdEnabled = new AtomicBoolean(false);
        RecordingFeature first = new RecordingFeature("first", harness.events);
        RecordingFeature second = new RecordingFeature("second", harness.events);
        RecordingFeature third = new RecordingFeature("third", harness.events);
        first.onEnable = () -> secondEnabled.set(false);
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("first", policy(true, true, false, false), () -> true, first),
                registration("second", policy(true, true, false, false),
                        secondEnabled::get, second),
                registration("third", policy(true, true, false, false),
                        thirdEnabled::get, third));

        long generation = harness.startMacro(lifecycle);
        FeatureLifecycleResult duplicate = lifecycle.macroStarted(generation, WORLD_EPOCH);

        assertTrue(duplicate.succeeded());
        assertFalse(duplicate.changed());
        assertEquals(List.of("first:enable", "second:enable"), harness.events);
        assertEquals(List.of(id("first"), id("second")), lifecycle.activeFeatures());

        harness.macro.stop(MacroTerminalReason.CLIENT_STOP);

        assertEquals(List.of(
                "first:enable", "second:enable",
                "first:stop:MACRO_STOPPED", "second:stop:MACRO_STOPPED"), harness.events);
        assertTrue(lifecycle.identity().isEmpty());
        assertEquals(FeatureState.STOPPED, lifecycle.state(id("first")));
        assertEquals(FeatureState.STOPPED, lifecycle.state(id("second")));
    }

    @Test
    void nestedFeatureSuspensionsWaitForEveryFeatureAndOtherMacroCause() {
        Harness harness = new Harness();
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("visitors", policy(false, false, true, false),
                        new RecordingFeature("visitors", harness.events)),
                registration("autosell", policy(false, false, true, false),
                        new RecordingFeature("autosell", harness.events)));
        harness.startMacro(lifecycle);

        assertTrue(lifecycle.enable(id("visitors")).succeeded());
        assertTrue(lifecycle.enable(id("autosell")).succeeded());
        assertEquals(MacroState.PAUSED, harness.macro.state());
        assertEquals(List.of(id("visitors"), id("autosell")),
                lifecycle.macroPausingFeatures());

        harness.macro.manualPause();
        lifecycle.stop(id("visitors"), FeatureStopCause.REQUESTED);
        lifecycle.stop(id("autosell"), FeatureStopCause.REQUESTED);

        assertEquals(MacroState.PAUSED, harness.macro.state());
        assertEquals(Set.of(MacroPauseCause.MANUAL), harness.macro.pauseCauses());
        harness.macro.manualResume();
        assertEquals(MacroState.RUNNING, harness.macro.state());
        assertEquals(List.of(
                "visitors:enable", "autosell:enable",
                "visitors:stop:REQUESTED", "autosell:stop:REQUESTED"), harness.events);
    }

    @Test
    void macroPauseAndResumeAreIdempotentAndFenceStaleGenerations() {
        Harness harness = new Harness();
        RecordingFeature tracker = new RecordingFeature("tracker", harness.events);
        RecordingFeature foreground = new RecordingFeature("foreground", harness.events);
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("tracker", policy(true, true, false, false), tracker),
                registration("foreground", policy(true, false, false, false), foreground));
        long firstGeneration = harness.startMacro(lifecycle);

        harness.macro.manualPause();
        FeatureLifecycleResult duplicatePause = lifecycle.macroPaused(
                firstGeneration, Set.of(MacroPauseCause.MANUAL));
        assertFalse(duplicatePause.changed());
        assertEquals(FeatureState.PAUSED, lifecycle.state(id("tracker")));
        assertEquals(FeatureState.RUNNING, lifecycle.state(id("foreground")));

        harness.macro.manualResume();
        assertEquals(FeatureState.RUNNING, lifecycle.state(id("tracker")));
        harness.macro.stop(MacroTerminalReason.WORLD_CHANGE);
        long secondGeneration = harness.startMacro(lifecycle);

        assertFalse(lifecycle.macroStopped(
                firstGeneration, MacroTerminalReason.DISCONNECT).changed());
        assertEquals(secondGeneration, lifecycle.identity().orElseThrow().macroGeneration());
        assertEquals(List.of(
                "tracker:enable", "foreground:enable", "tracker:pause", "tracker:resume",
                "tracker:stop:MACRO_STOPPED", "foreground:stop:MACRO_STOPPED",
                "tracker:enable", "foreground:enable"), harness.events);
    }

    @Test
    void pauseFailureStopsOnlyBrokenFeatureAndCannotBreakMacroLeaseAcquisition() {
        Harness harness = new Harness();
        RecordingFeature broken = new RecordingFeature("broken", harness.events);
        broken.failPause = true;
        RecordingFeature pauser = new RecordingFeature("pauser", harness.events);
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("broken", policy(true, true, false, false), broken),
                registration("pauser", policy(false, false, true, false), pauser));
        harness.startMacro(lifecycle);

        FeatureLifecycleResult activation = lifecycle.enable(id("pauser"));

        assertTrue(activation.succeeded());
        assertEquals(MacroState.PAUSED, harness.macro.state());
        assertEquals(FeatureState.STOPPED, lifecycle.state(id("broken")));
        assertEquals(FeatureState.RUNNING, lifecycle.state(id("pauser")));
        assertTrue(harness.bridge.lastResult.failed());

        lifecycle.stop(id("pauser"), FeatureStopCause.REQUESTED);
        assertEquals(MacroState.RUNNING, harness.macro.state());
        assertEquals(List.of(
                "broken:enable", "broken:pause", "broken:stop:CALLBACK_FAILED",
                "pauser:enable", "pauser:stop:REQUESTED"), harness.events);
    }

    @Test
    void failedAutoStartRollsBackEarlierFeaturesAndReleasesMacroSuspension() {
        Harness harness = new Harness();
        RecordingFeature pauser = new RecordingFeature("pauser", harness.events);
        RecordingFeature broken = new RecordingFeature("broken", harness.events);
        broken.failEnable = true;
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("pauser", policy(true, false, true, false), pauser),
                registration("broken", policy(true, false, false, false), broken));

        harness.macro.start();
        long generation = harness.macro.generation();
        FeatureLifecycleResult result = lifecycle.macroStarted(generation, WORLD_EPOCH);

        assertTrue(result.failed());
        assertFalse(result.changed());
        assertTrue(lifecycle.identity().isEmpty());
        assertTrue(lifecycle.activeFeatures().isEmpty());
        assertEquals(MacroState.RUNNING, harness.macro.state());
        assertEquals(List.of(
                "pauser:enable", "broken:enable", "broken:stop:CALLBACK_FAILED",
                "pauser:stop:START_ROLLBACK"), harness.events);

        harness.macro.stop(MacroTerminalReason.EXCEPTION);
    }

    @Test
    void stopAllContinuesAfterFailureAndReleasesEverySuspension() {
        Harness harness = new Harness();
        RecordingFeature first = new RecordingFeature("first", harness.events);
        first.failStop = true;
        RecordingFeature second = new RecordingFeature("second", harness.events);
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("first", policy(false, false, true, false), first),
                registration("second", policy(false, false, true, false), second));
        harness.startMacro(lifecycle);
        lifecycle.enable(id("first"));
        lifecycle.enable(id("second"));

        FeatureLifecycleResult result = lifecycle.stopAll(FeatureStopCause.REQUESTED);

        assertTrue(result.changed());
        assertTrue(result.failed());
        assertTrue(lifecycle.activeFeatures().isEmpty());
        assertEquals(MacroState.RUNNING, harness.macro.state());
        assertEquals(List.of(
                "first:enable", "second:enable",
                "first:stop:REQUESTED", "second:stop:REQUESTED"), harness.events);
    }

    @Test
    void stopAllExceptAndResetUseRegistrationOrder() {
        Harness harness = new Harness();
        RecordingFeature first = new RecordingFeature("first", harness.events);
        RecordingFeature retained = new RecordingFeature("retained", harness.events);
        RecordingFeature third = new RecordingFeature("third", harness.events);
        FeatureLifecycle lifecycle = harness.lifecycle(
                registration("first", policy(false, false, false, false), first),
                registration("retained", policy(false, false, false, false), retained),
                registration("third", policy(false, false, false, false), third));
        harness.startMacro(lifecycle);
        lifecycle.enable(id("first"));
        lifecycle.enable(id("retained"));
        lifecycle.enable(id("third"));

        lifecycle.stopAllExcept(Set.of(id("retained")), FeatureStopCause.EXCLUDED);
        assertEquals(List.of(id("retained")), lifecycle.activeFeatures());
        lifecycle.stop(id("retained"), FeatureStopCause.REQUESTED);
        FeatureLifecycleResult reset = lifecycle.resetAll();

        assertTrue(reset.succeeded());
        assertEquals(List.of(
                "first:enable", "retained:enable", "third:enable",
                "first:stop:EXCLUDED", "third:stop:EXCLUDED",
                "retained:stop:REQUESTED",
                "first:reset", "retained:reset", "third:reset"), harness.events);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.stopAllExcept(
                Set.of(id("missing")), FeatureStopCause.EXCLUDED));
    }

    @Test
    void foregroundSuppressionClearsPriorEvidenceAndGatesArbitrator() {
        Harness harness = new Harness();
        RecordingFeature foreground = new RecordingFeature("foreground", harness.events);
        FeatureLifecycle lifecycle = harness.lifecycle(registration(
                "foreground", policy(false, false, false, true), foreground));
        long generation = harness.startMacro(lifecycle);
        FailsafeCandidate candidate = new FailsafeCandidate(
                FailsafeType.DIRT, generation, WORLD_EPOCH);

        assertTrue(lifecycle.submitFailsafe(candidate).accepted());
        assertTrue(harness.arbitrator.hasPending());
        lifecycle.enable(id("foreground"));

        assertFalse(harness.arbitrator.hasPending());
        assertFalse(lifecycle.canCheckFailsafes());
        FeatureFailsafeSubmission suppressed = lifecycle.submitFailsafe(candidate);
        assertEquals(FeatureFailsafeSubmission.Status.SUPPRESSED, suppressed.status());
        assertEquals(List.of(id("foreground")), suppressed.suppressors());
        harness.clock.advanceMillis(2_000L);
        assertTrue(lifecycle.selectFailsafeIfReady().isEmpty());

        lifecycle.stop(id("foreground"), FeatureStopCause.REQUESTED);
        assertTrue(lifecycle.canCheckFailsafes());
        assertTrue(lifecycle.submitFailsafe(candidate).accepted());
        harness.clock.advanceMillis(2_000L);
        assertEquals(candidate, lifecycle.selectFailsafeIfReady().orElseThrow());

        FailsafeCandidate stale = new FailsafeCandidate(
                FailsafeType.COBWEB, generation, WORLD_EPOCH + 1L);
        assertEquals(FailsafeArbitrator.SubmissionStatus.STALE_IDENTITY,
                lifecycle.submitFailsafe(stale).arbitration().orElseThrow().status());
        FailsafeCandidate duplicate = new FailsafeCandidate(
                FailsafeType.COBWEB, generation, WORLD_EPOCH);
        assertEquals(FailsafeArbitrator.SubmissionStatus.ALREADY_TRIGGERED,
                lifecycle.submitFailsafe(duplicate).arbitration().orElseThrow().status());

        harness.macro.stop(MacroTerminalReason.CLIENT_STOP);
        assertEquals(FeatureFailsafeSubmission.Status.INACTIVE,
                lifecycle.submitFailsafe(candidate).status());
    }

    private static FeatureRegistration registration(
            String value,
            FeaturePolicy policy,
            FeatureLifecycleTarget target
    ) {
        return registration(value, policy, () -> true, target);
    }

    private static FeatureRegistration registration(
            String value,
            FeaturePolicy policy,
            BooleanSupplier enabled,
            FeatureLifecycleTarget target
    ) {
        return new FeatureRegistration(id(value), policy, enabled, target);
    }

    private static FeaturePolicy policy(
            boolean startAtMacroStart,
            boolean pauseWithMacro,
            boolean pausesMacro,
            boolean suppressFailsafes
    ) {
        return new FeaturePolicy(
                startAtMacroStart,
                pauseWithMacro,
                pausesMacro,
                suppressFailsafes
                        ? FeatureFailsafePolicy.SUPPRESS_CHECKS
                        : FeatureFailsafePolicy.ALLOW_CHECKS);
    }

    private static FeatureId id(String value) {
        return new FeatureId(value);
    }

    private static final class Harness {
        private final List<String> events = new ArrayList<>();
        private final TestClock clock = new TestClock();
        private final BridgeTarget bridge = new BridgeTarget();
        private final MacroLifecycle macro = new MacroLifecycle(bridge, clock);
        private final FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(
                clock, () -> 2_000L);

        private FeatureLifecycle lifecycle(FeatureRegistration... registrations) {
            FeatureLifecycle lifecycle = new FeatureLifecycle(macro, arbitrator, registrations);
            bridge.lifecycle = lifecycle;
            return lifecycle;
        }

        private long startMacro(FeatureLifecycle lifecycle) {
            macro.start();
            long generation = macro.generation();
            FeatureLifecycleResult result = lifecycle.macroStarted(generation, WORLD_EPOCH);
            assertTrue(result.succeeded());
            return generation;
        }
    }

    private static final class BridgeTarget implements MacroLifecycleTarget {
        private FeatureLifecycle lifecycle;
        private FeatureLifecycleResult lastResult = FeatureLifecycleResult.unchanged();

        @Override
        public void start(long generation, long nowNanos) {
        }

        @Override
        public void pause(long generation, long nowNanos, Set<MacroPauseCause> causes) {
            if (lifecycle != null) {
                lastResult = lifecycle.macroPaused(generation, causes);
            }
        }

        @Override
        public void resume(long generation, long nowNanos) {
            if (lifecycle != null) {
                lastResult = lifecycle.macroResumed(generation);
            }
        }

        @Override
        public void stop(long generation, MacroTerminalReason reason) {
            if (lifecycle != null) {
                lastResult = lifecycle.macroStopped(generation, reason);
            }
        }
    }

    private static final class RecordingFeature implements FeatureLifecycleTarget {
        private final String name;
        private final List<String> events;
        private Runnable onEnable = () -> { };
        private boolean failEnable;
        private boolean failPause;
        private boolean failStop;

        private RecordingFeature(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void enable(FeatureRunIdentity identity) {
            events.add(name + ":enable");
            onEnable.run();
            if (failEnable) {
                throw new IllegalStateException(name + " enable failed");
            }
        }

        @Override
        public void pause(FeatureRunIdentity identity, Set<MacroPauseCause> causes) {
            events.add(name + ":pause");
            if (failPause) {
                throw new IllegalStateException(name + " pause failed");
            }
        }

        @Override
        public void resume(FeatureRunIdentity identity) {
            events.add(name + ":resume");
        }

        @Override
        public void stop(FeatureRunIdentity identity, FeatureStopCause cause) {
            events.add(name + ":stop:" + cause);
            if (failStop) {
                throw new IllegalStateException(name + " stop failed");
            }
        }

        @Override
        public void reset() {
            events.add(name + ":reset");
        }
    }

    private static final class TestClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void advanceMillis(long millis) {
            nowNanos += millis * 1_000_000L;
        }
    }
}
