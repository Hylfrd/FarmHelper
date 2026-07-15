package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.runtime.ClientTickPipeline;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;

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
                runtime.core().macroManager().tick(
                        observed, ClientTickAdapter.macroContext(observed));
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
}
