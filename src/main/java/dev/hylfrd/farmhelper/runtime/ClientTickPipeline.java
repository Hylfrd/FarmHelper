package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Fixed, client-thread ordering for one runtime tick with fail-closed exception delivery. */
public final class ClientTickPipeline {
    public enum Stage {
        CLIENT_SNAPSHOT,
        LIFECYCLE,
        GAME_TEXT,
        TASK_QUEUE,
        GAME_STATE_PARSE,
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
        ClientSnapshot captureClientSnapshot();

        void observeLifecycle(ClientSnapshot snapshot);

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
        Stage stage = Stage.CLIENT_SNAPSHOT;
        try {
            ClientSnapshot snapshot = Objects.requireNonNull(
                    actions.captureClientSnapshot(), "client snapshot");
            stage = Stage.LIFECYCLE;
            actions.observeLifecycle(snapshot);
            stage = Stage.GAME_TEXT;
            RawGameTextSnapshot raw = Objects.requireNonNull(
                    actions.captureGameText(snapshot), "game text snapshot");
            stage = Stage.TASK_QUEUE;
            actions.advanceTaskQueue();
            stage = Stage.GAME_STATE_PARSE;
            GameStateParseResult gameState = Objects.requireNonNull(
                    actions.parseGameState(snapshot, raw), "game state result");
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
