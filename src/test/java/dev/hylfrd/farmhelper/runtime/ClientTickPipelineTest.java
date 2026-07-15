package dev.hylfrd.farmhelper.runtime;

import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParser;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickPipelineTest {
    @Test
    void parsesCurrentStateBeforeAdvancingDueStatefulTasks() {
        List<String> order = new ArrayList<>();
        GameStateParseResult[] current = new GameStateParseResult[1];
        ClientTickPipeline pipeline = new ClientTickPipeline();

        assertTrue(pipeline.tick(new Actions(order) {
            @Override
            public GameStateParseResult parseGameState(ClientSnapshot snapshot, RawGameTextSnapshot raw) {
                order.add("parse");
                current[0] = new GameStateParser().parse(snapshot, raw);
                parsed = current[0];
                return current[0];
            }

            @Override
            public void advanceTaskQueue() {
                order.add("tasks");
                assertSame(parsed, current[0]);
            }
        }).isEmpty());

        assertEquals(List.of("lifecycle", "snapshot", "snapshot-lifecycle", "text", "parse",
                "tasks", "runtime", "rotation", "input"), order);
    }

    @Test
    void reportsTheExactFailingStageAndDoesNotRunLaterStages() {
        List<String> order = new ArrayList<>();
        ClientTickPipeline.Failure failure = new ClientTickPipeline().tick(new Actions(order) {
            @Override
            public void advanceTaskQueue() {
                order.add("tasks");
                throw new IllegalStateException("boom");
            }
        }).orElseThrow();

        assertEquals(ClientTickPipeline.Stage.TASK_QUEUE, failure.stage());
        assertEquals("boom", failure.cause().getMessage());
        assertEquals(List.of("lifecycle", "snapshot", "snapshot-lifecycle", "text", "parse",
                "tasks", "failure"), order);
    }

    private static class Actions implements ClientTickPipeline.Actions {
        private final List<String> order;
        private final ClientSnapshot snapshot = ClientSnapshot.unknown();
        private final RawGameTextSnapshot raw = RawGameTextSnapshot.unknown(0L);
        protected GameStateParseResult parsed;

        private Actions(List<String> order) {
            this.order = order;
        }

        @Override
        public void observeClientLifecycle() {
            order.add("lifecycle");
        }

        @Override
        public ClientSnapshot captureClientSnapshot() {
            order.add("snapshot");
            return snapshot;
        }

        @Override
        public void observeSnapshotLifecycle(ClientSnapshot ignored) {
            order.add("snapshot-lifecycle");
        }

        @Override
        public RawGameTextSnapshot captureGameText(ClientSnapshot ignored) {
            order.add("text");
            return raw;
        }

        @Override
        public void advanceTaskQueue() {
            order.add("tasks");
        }

        @Override
        public GameStateParseResult parseGameState(ClientSnapshot ignored, RawGameTextSnapshot ignoredRaw) {
            order.add("parse");
            parsed = new GameStateParser().parse(snapshot, raw);
            return parsed;
        }

        @Override
        public void deliverRuntimeTick(ClientSnapshot ignored, GameStateParseResult ignoredState) {
            order.add("runtime");
        }

        @Override
        public void tickRotation() {
            order.add("rotation");
        }

        @Override
        public void enforceInputSafety(ClientSnapshot ignored) {
            order.add("input");
        }

        @Override
        public void onFailure(ClientTickPipeline.Failure ignored) {
            order.add("failure");
        }
    }
}
