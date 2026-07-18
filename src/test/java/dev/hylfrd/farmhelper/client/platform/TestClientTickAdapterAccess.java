package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.runtime.ClientTickPipeline;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Test-only access to the exact package-private adapter actions used by the live pipeline. */
public final class TestClientTickAdapterAccess {
    private TestClientTickAdapterAccess() {
    }

    public static void enforceInputSafety(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot
    ) {
        ClientTickAdapter.enforceInputSafety(runtime, snapshot);
    }

    public static void applyMacroRotationDisposition(
            FarmHelperClientRuntime runtime,
            MacroDecision decision
    ) {
        ClientTickAdapter.applyMacroRotationDisposition(runtime, decision);
    }

    public static void applyManagedDecision(
            FarmHelperClientRuntime runtime,
            MacroDecision decision
    ) {
        ClientTickAdapter.applyManagedDecision(runtime, decision);
    }

    public static void applyDecision(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot,
            MacroDecision decision
    ) {
        ClientTickAdapter.applyManagedDecision(runtime, decision);
        decision.rotation().ifPresent(request ->
                ClientTickAdapter.startRotation(runtime, snapshot, request));
    }

    public static Observation<SpatialSnapshot> captureMacroSpatial(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot
    ) {
        return ClientTickAdapter.captureMacroSpatial(runtime, snapshot);
    }

    /** Executes the complete production stage order while delegating adapter-owned tail actions. */
    public static Optional<ClientTickPipeline.Failure> tick(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot,
            Runnable snapshotLifecycle
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshotLifecycle, "snapshotLifecycle");
        return new ClientTickPipeline().tick(new ClientTickPipeline.Actions() {
            @Override
            public void observeClientLifecycle() {
            }

            @Override
            public ClientSnapshot captureClientSnapshot() {
                return snapshot;
            }

            @Override
            public void observeSnapshotLifecycle(ClientSnapshot observed) {
                snapshotLifecycle.run();
            }

            @Override
            public RawGameTextSnapshot captureGameText(ClientSnapshot observed) {
                return RawGameTextSnapshot.unknown(runtime.lifecycle().worldEpoch());
            }

            @Override
            public GameStateParseResult parseGameState(
                    ClientSnapshot observed,
                    RawGameTextSnapshot raw
            ) {
                return runtime.core().parseGameState(observed, raw);
            }

            @Override
            public void advanceTaskQueue() {
                runtime.core().taskQueue().advance();
            }

            @Override
            public void deliverRuntimeTick(
                    ClientSnapshot observed,
                    GameStateParseResult gameState
            ) {
                runtime.tickDesync();
                runtime.core().macroManager().tick(
                        observed, new FarmingContext(
                                runtime.core().nowNanos(), runtime.lifecycle().worldEpoch(),
                                observed.player(), Observation.unknown(),
                                gameState.snapshot().inGarden(), false,
                                runtime.core().serverResponsiveness(
                                        observed.connection().isPresent())));
            }

            @Override
            public void tickRotation() {
            }

            @Override
            public void enforceInputSafety(ClientSnapshot observed) {
                ClientTickAdapter.enforceInputSafety(runtime, observed);
            }

            @Override
            public void onFailure(ClientTickPipeline.Failure failure) {
                runtime.failed();
            }
        });
    }

    public static Optional<ClientTickPipeline.Failure> decisionBeforeRotation(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot,
            MacroDecision decision,
            Runnable rotationStage
    ) {
        return new ClientTickPipeline().tick(new ClientTickPipeline.Actions() {
            @Override public void observeClientLifecycle() { }
            @Override public ClientSnapshot captureClientSnapshot() { return snapshot; }
            @Override public void observeSnapshotLifecycle(ClientSnapshot observed) { }
            @Override public RawGameTextSnapshot captureGameText(ClientSnapshot observed) {
                return RawGameTextSnapshot.unknown(runtime.lifecycle().worldEpoch());
            }
            @Override public GameStateParseResult parseGameState(
                    ClientSnapshot observed, RawGameTextSnapshot raw) {
                return runtime.core().parseGameState(observed, raw);
            }
            @Override public void advanceTaskQueue() { }
            @Override public void deliverRuntimeTick(
                    ClientSnapshot observed, GameStateParseResult gameState) {
                applyDecision(runtime, observed, decision);
            }
            @Override public void tickRotation() { rotationStage.run(); }
            @Override public void enforceInputSafety(ClientSnapshot observed) { }
            @Override public void onFailure(ClientTickPipeline.Failure failure) { runtime.failed(); }
        });
    }
}
