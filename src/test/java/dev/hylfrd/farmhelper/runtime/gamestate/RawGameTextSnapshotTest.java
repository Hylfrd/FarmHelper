package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawGameTextSnapshotTest {
    @Test
    void deeplyCopiesEveryMutableBatch() {
        List<String> scoreboard = new ArrayList<>(List.of("Purse: 0"));
        List<String> tab = new ArrayList<>(List.of("Area: Garden"));
        List<String> footer = new ArrayList<>(List.of("Active Effects"));
        List<String> lore = new ArrayList<>(List.of("Vacuum Bag: 0 Pests"));
        List<RawChatMessage> chat = new ArrayList<>(List.of(new RawChatMessage(1, "YUCK!")));

        RawGameTextSnapshot snapshot = new RawGameTextSnapshot(
                Observation.present("SKYBLOCK"), Observation.present(scoreboard),
                Observation.present(tab), Observation.present(footer), Observation.present(lore),
                Observation.present(chat), PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE), 7);
        scoreboard.clear();
        tab.clear();
        footer.clear();
        lore.clear();
        chat.clear();

        assertEquals(List.of("Purse: 0"), snapshot.scoreboardLines().get());
        assertEquals(List.of("Area: Garden"), snapshot.tabLines().get());
        assertEquals(1, snapshot.chatBatch().get().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.scoreboardLines().get().add("Bits: 0"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.chatBatch().get().add(new RawChatMessage(2, "EWW!")));
    }

    @Test
    void preservesAbsentUnknownAndLegalEmptyInputs() {
        RawGameTextSnapshot snapshot = new RawGameTextSnapshot(
                Observation.absent(), Observation.present(List.of()), Observation.unknown(),
                Observation.absent(), Observation.present(List.of()), Observation.present(List.of()),
                new PlayerFacts(Observation.present(true), Observation.present(0), Observation.present(0.0D)),
                Observation.present(WorldTransition.STABLE), 0);

        assertTrue(snapshot.scoreboardTitle().isAbsent());
        assertTrue(snapshot.scoreboardLines().isPresent());
        assertTrue(snapshot.scoreboardLines().get().isEmpty());
        assertTrue(snapshot.tabLines().isUnknown());
        assertEquals(0.0D, snapshot.playerFacts().walkSpeedFactor().get());
    }

    @Test
    void rejectsNegativeGenerationAndChatSequence() {
        assertThrows(IllegalArgumentException.class, () -> RawGameTextSnapshot.unknown(-1));
        assertThrows(IllegalArgumentException.class, () -> new RawChatMessage(-1, "message"));
    }
}
