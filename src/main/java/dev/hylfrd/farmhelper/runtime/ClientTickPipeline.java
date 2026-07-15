package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Fixed, client-thread ordering for one runtime tick with fail-closed exception delivery. */
public final class ClientTickPipeline {
    public enum Stage {
        CLIENT_LIFECYCLE,
        CLIENT_SNAPSHOT,
        SNAPSHOT_LIFECYCLE,
        GAME_TEXT,
        GAME_STATE_PARSE,
        TASK_QUEUE,
        RUNTIME_DELIVERY,
        ROTATION,
        INPUT_SAFETY
    }

    public record Failure(Stage stage, Throwable cause) {
        public Failure {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(cause, "cause");
        }
    }

    public interface Actions {
        void observeClientLifecycle();

        ClientSnapshot captureClientSnapshot();

        void observeSnapshotLifecycle(ClientSnapshot snapshot);

        RawGameTextSnapshot captureGameText(ClientSnapshot snapshot);

        void advanceTaskQueue();

        GameStateParseResult parseGameState(ClientSnapshot snapshot, RawGameTextSnapshot raw);

        void deliverRuntimeTick(ClientSnapshot snapshot, GameStateParseResult gameState);

        void tickRotation();

        void enforceInputSafety(ClientSnapshot snapshot);

        void onFailure(Failure failure);
    }

    public Optional<Failure> tick(Actions actions) {
        Objects.requireNonNull(actions, "actions");
        Stage stage = Stage.CLIENT_LIFECYCLE;
        try {
            actions.observeClientLifecycle();
            stage = Stage.CLIENT_SNAPSHOT;
            ClientSnapshot snapshot = Objects.requireNonNull(
                    actions.captureClientSnapshot(), "client snapshot");
            stage = Stage.SNAPSHOT_LIFECYCLE;
            actions.observeSnapshotLifecycle(snapshot);
            stage = Stage.GAME_TEXT;
            RawGameTextSnapshot raw = Objects.requireNonNull(
                    actions.captureGameText(snapshot), "game text snapshot");
            stage = Stage.GAME_STATE_PARSE;
            GameStateParseResult gameState = Objects.requireNonNull(
                    actions.parseGameState(snapshot, raw), "game state result");
            stage = Stage.TASK_QUEUE;
            actions.advanceTaskQueue();
            stage = Stage.RUNTIME_DELIVERY;
            actions.deliverRuntimeTick(snapshot, gameState);
            stage = Stage.ROTATION;
            actions.tickRotation();
            stage = Stage.INPUT_SAFETY;
            actions.enforceInputSafety(snapshot);
            return Optional.empty();
        } catch (RuntimeException | Error exception) {
            Failure failure = new Failure(stage, exception);
            try {
                actions.onFailure(failure);
            } catch (RuntimeException | Error cancellationFailure) {
                exception.addSuppressed(cancellationFailure);
            }
            return Optional.of(failure);
        }
    }
}
