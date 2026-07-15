package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParser;
import dev.hylfrd.farmhelper.runtime.gamestate.PlayerFacts;
import dev.hylfrd.farmhelper.runtime.gamestate.RawChatMessage;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.SemanticLocation;
import dev.hylfrd.farmhelper.runtime.gamestate.WorldTransition;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTransitionParserSequenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stableReplacementUnloadLoadChatResetAndDisconnectKeepExactLocationPriority() {
        GameStateParser parser = new GameStateParser();
        WorldTransitionTracker transitions = new WorldTransitionTracker();
        SequencedGameTextSource gameText = new SequencedGameTextSource();
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("parser-sequence.json"));
        ClientLifecycleSequencer sequencer = new ClientLifecycleSequencer(runtime, gameText);
        long generation = 0L;

        Object firstLevel = new Object();
        sequencer.observeLevel(firstLevel, true);
        assertEquals(1L, runtime.lifecycle().worldEpoch());
        assertEquals(1, gameText.resets);
        ClientSnapshot firstWorld = connected(world(runtime.lifecycle().worldEpoch()), player());
        sequencer.observeConnection(firstWorld.connection());
        assertLocation(SemanticLocation.GARDEN, parser, firstWorld,
                raw(transitions.observe(firstWorld.world()), gameText.drain(), generation++));

        gameText.acceptChat("chat", "You were spawned in Limbo.");
        Object replacementLevel = new Object();
        sequencer.observeLevel(replacementLevel, true);
        assertEquals(3L, runtime.lifecycle().worldEpoch());
        assertEquals(3, gameText.resets);
        ClientSnapshot replacement = connected(world(runtime.lifecycle().worldEpoch()), player());
        sequencer.observeConnection(replacement.connection());
        assertLocation(SemanticLocation.TELEPORTING, parser, replacement,
                raw(transitions.observe(replacement.world()), gameText.drain(), generation++));

        sequencer.observeLevel(null, false);
        assertEquals(4L, runtime.lifecycle().worldEpoch());
        assertEquals(4, gameText.resets);
        ClientSnapshot unloaded = connected(Observation.absent(), Observation.absent());
        sequencer.observeConnection(unloaded.connection());
        assertLocation(SemanticLocation.TELEPORTING, parser, unloaded,
                raw(transitions.observe(unloaded.world()), gameText.drain(), generation++));

        Object loadedLevel = new Object();
        sequencer.observeLevel(loadedLevel, true);
        assertEquals(5L, runtime.lifecycle().worldEpoch());
        assertEquals(5, gameText.resets);
        ClientSnapshot loaded = connected(world(runtime.lifecycle().worldEpoch()), player());
        sequencer.observeConnection(loaded.connection());
        assertLocation(SemanticLocation.TELEPORTING, parser, loaded,
                raw(transitions.observe(loaded.world()), gameText.drain(), generation++));

        sequencer.observeLevel(loadedLevel, false);
        gameText.acceptChat("chat", "You were spawned in Limbo.");
        assertLocation(SemanticLocation.LIMBO, parser, loaded,
                raw(transitions.observe(loaded.world()), gameText.drain(), generation++));

        gameText.acceptChat("chat", "You were spawned in Limbo.");
        sequencer.resetChat();
        assertEquals(6, gameText.resets);
        assertLocation(SemanticLocation.GARDEN, parser, loaded,
                raw(transitions.observe(loaded.world()), gameText.drain(), generation++));

        gameText.acceptChat("chat", "You were spawned in Limbo.");
        sequencer.disconnect();
        assertEquals(6L, runtime.lifecycle().worldEpoch());
        assertEquals(7, gameText.resets);
        Observation<WorldSnapshot> disconnectedWorld = Observation.absent();
        ClientSnapshot disconnected = new ClientSnapshot(
                Observation.absent(), disconnectedWorld, Observation.absent(), Observation.absent());
        sequencer.observeConnection(disconnected.connection());
        Observation<List<RawChatMessage>> disconnectedBatch = gameText.drain();
        assertTrue(disconnectedBatch.isPresent());
        assertTrue(disconnectedBatch.get().isEmpty());
        RawGameTextSnapshot disconnectedRaw = raw(
                transitions.observe(disconnectedWorld), disconnectedBatch, generation);
        assertTrue(parser.parse(disconnected, disconnectedRaw).snapshot().location().isAbsent());
    }

    private static void assertLocation(
            SemanticLocation expected,
            GameStateParser parser,
            ClientSnapshot client,
            RawGameTextSnapshot raw
    ) {
        assertEquals(expected, parser.parse(client, raw).snapshot().location().get());
    }

    private static RawGameTextSnapshot raw(
            WorldTransition transition,
            Observation<List<RawChatMessage>> chat,
            long generation
    ) {
        return new RawGameTextSnapshot(
                Observation.present("SKYBLOCK"),
                Observation.present(List.of()),
                Observation.present(List.of("Area: Garden")),
                Observation.present(List.of("Active Effects")),
                Observation.absent(),
                chat,
                PlayerFacts.unknown(),
                Observation.present(transition),
                generation);
    }

    private static ClientSnapshot connected(
            Observation<WorldSnapshot> world,
            Observation<PlayerSnapshot> player
    ) {
        return new ClientSnapshot(
                player,
                world,
                Observation.present(ConnectionSnapshot.multiplayer()),
                Observation.absent());
    }

    private static Observation<WorldSnapshot> world(long epoch) {
        return Observation.present(new WorldSnapshot(
                epoch, Observation.present(ResourceIdentifier.parse("minecraft:overworld"))));
    }

    private static Observation<PlayerSnapshot> player() {
        return Observation.present(new PlayerSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown()));
    }

    private static final class SequencedGameTextSource implements ClientGameTextSource {
        private final BoundedChatBuffer chat = new BoundedChatBuffer();
        private int resets;

        @Override
        public void acceptChat(String channel, String text) {
            chat.accept(channel, text);
        }

        @Override
        public void resetChat() {
            resets++;
            chat.reset();
        }

        private Observation<List<RawChatMessage>> drain() {
            return chat.drain();
        }

        @Override
        public RawGameTextSnapshot snapshot(ClientSnapshot client) {
            return RawGameTextSnapshot.unknown(0L);
        }
    }
}
