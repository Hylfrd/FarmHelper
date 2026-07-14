package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
        assertTrue(snapshot.inputDiagnostics().isEmpty());
    }

    @Test
    void enforcesEveryCollectionCountBeforeCopyingIncludingHugeViews() {
        assertTrue(snapshotWithScoreboard(Collections.nCopies(
                GameTextInputBudget.MAX_SCOREBOARD_LINES, "x")).scoreboardLines().isPresent());
        assertTrue(snapshotWithScoreboard(Collections.nCopies(
                GameTextInputBudget.MAX_SCOREBOARD_LINES + 1, "x")).scoreboardLines().isUnknown());
        assertTrue(snapshotWithTab(Collections.nCopies(
                GameTextInputBudget.MAX_TAB_LINES, "x")).tabLines().isPresent());
        assertTrue(snapshotWithTab(Collections.nCopies(
                GameTextInputBudget.MAX_TAB_LINES + 1, "x")).tabLines().isUnknown());
        assertTrue(snapshotWithFooter(Collections.nCopies(
                GameTextInputBudget.MAX_TAB_FOOTER_LINES, "x")).tabFooter().isPresent());
        assertTrue(snapshotWithFooter(Collections.nCopies(
                GameTextInputBudget.MAX_TAB_FOOTER_LINES + 1, "x")).tabFooter().isUnknown());
        assertTrue(snapshotWithLore(Collections.nCopies(
                GameTextInputBudget.MAX_VACUUM_LORE_LINES, "x")).vacuumLore().isPresent());
        assertTrue(snapshotWithLore(Collections.nCopies(
                GameTextInputBudget.MAX_VACUUM_LORE_LINES + 1, "x")).vacuumLore().isUnknown());

        RawChatMessage message = new RawChatMessage(0, "x");
        assertTrue(snapshotWithChat(Collections.nCopies(
                GameTextInputBudget.MAX_CHAT_MESSAGES, message)).chatBatch().isPresent());
        assertTrue(snapshotWithChat(Collections.nCopies(
                GameTextInputBudget.MAX_CHAT_MESSAGES + 1, message)).chatBatch().isUnknown());

        NeverReadHugeList<String> hugeView = new NeverReadHugeList<>(10_000_000);
        RawGameTextSnapshot huge = snapshotWithScoreboard(hugeView);
        assertTrue(huge.scoreboardLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)), huge.inputDiagnostics());
        assertEquals(0, hugeView.getCalls);
    }

    @Test
    void enforcesLineAndTotalCharacterBoundariesIncludingSurrogates() {
        String exactLine = "😀".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS / 2);
        String overLine = exactLine + "\uD83D";
        RawGameTextSnapshot exact = snapshot(
                Observation.present(exactLine), List.of(exactLine), List.of(exactLine),
                List.of(exactLine), List.of(exactLine), List.of(new RawChatMessage(0, exactLine)));
        assertTrue(exact.scoreboardTitle().isPresent());
        assertTrue(exact.scoreboardLines().isPresent());
        assertTrue(exact.tabLines().isPresent());
        assertTrue(exact.tabFooter().isPresent());
        assertTrue(exact.vacuumLore().isPresent());
        assertTrue(exact.chatBatch().isPresent());

        RawGameTextSnapshot over = snapshot(
                Observation.present(overLine), List.of(overLine), List.of(overLine),
                List.of(overLine), List.of(overLine), List.of(new RawChatMessage(0, overLine)));
        assertTrue(over.scoreboardTitle().isUnknown());
        assertTrue(over.scoreboardLines().isUnknown());
        assertTrue(over.tabLines().isUnknown());
        assertTrue(over.tabFooter().isUnknown());
        assertTrue(over.vacuumLore().isUnknown());
        assertTrue(over.chatBatch().isUnknown());
        assertEquals(6, over.inputDiagnostics().size());
        assertTrue(over.inputDiagnostics().stream().allMatch(
                diagnostic -> diagnostic.code() == ParseDiagnosticCode.INPUT_LIMIT));

        String maximum = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS);
        int lineCount = Math.toIntExact(
                GameTextInputBudget.MAX_TOTAL_CHARACTERS / GameTextInputBudget.MAX_LINE_CHARACTERS);
        List<String> maximumTotal = Collections.nCopies(lineCount, maximum);
        RawGameTextSnapshot totalAtLimit = snapshot(
                Observation.absent(), maximumTotal, List.of(), List.of(), List.of(), List.of());
        assertTrue(totalAtLimit.scoreboardLines().isPresent());
        assertTrue(totalAtLimit.inputDiagnostics().isEmpty());

        RawGameTextSnapshot totalOverLimit = snapshot(
                Observation.present("x"), maximumTotal, List.of(), List.of(), List.of(), List.of());
        assertTrue(totalOverLimit.scoreboardTitle().isUnknown());
        assertTrue(totalOverLimit.scoreboardLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT)),
                totalOverLimit.inputDiagnostics());
    }

    @Test
    void rejectsScoreboardPlusTabCounterexampleBeforeCopying() {
        String maximum = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS);
        ProbeList<String> scoreboard = new ProbeList<>(Collections.nCopies(32, maximum));
        ProbeList<String> tab = new ProbeList<>(List.of("x"));

        RawGameTextSnapshot snapshot = snapshot(
                Observation.absent(), scoreboard, tab, List.of(), List.of(), List.of());

        assertTrue(snapshot.scoreboardLines().isUnknown());
        assertTrue(snapshot.tabLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
        assertEquals(32, scoreboard.getCalls);
        assertEquals(1, tab.getCalls);
        assertEquals(0, scoreboard.iteratorCalls);
        assertEquals(0, tab.iteratorCalls);
        assertEquals(31, scoreboard.highestIndex);
        assertEquals(0, tab.highestIndex);
    }

    @Test
    void observesExactAndOverTotalAcrossEveryCollectionUsingUtf16Characters() {
        String maximum = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS);
        ProbeList<String> exactScoreboard = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> exactTab = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> exactFooter = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> exactLore = new ProbeList<>(Collections.nCopies(7, maximum));
        ProbeList<RawChatMessage> exactChat = new ProbeList<>(List.of(
                new RawChatMessage(0, "😀", "x".repeat(2_046))));

        RawGameTextSnapshot exact = snapshot(
                Observation.absent(), exactScoreboard, exactTab, exactFooter, exactLore, exactChat);

        assertTrue(exact.scoreboardLines().isPresent());
        assertTrue(exact.tabLines().isPresent());
        assertTrue(exact.tabFooter().isPresent());
        assertTrue(exact.vacuumLore().isPresent());
        assertTrue(exact.chatBatch().isPresent());
        assertTrue(exact.inputDiagnostics().isEmpty());
        assertProbeTraversals(exactScoreboard, 16, 7);
        assertProbeTraversals(exactTab, 16, 7);
        assertProbeTraversals(exactFooter, 16, 7);
        assertProbeTraversals(exactLore, 14, 6);
        assertProbeTraversals(exactChat, 2, 0);

        ProbeList<String> overScoreboard = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> overTab = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> overFooter = new ProbeList<>(Collections.nCopies(8, maximum));
        ProbeList<String> overLore = new ProbeList<>(Collections.nCopies(7, maximum));
        ProbeList<RawChatMessage> overChat = new ProbeList<>(List.of(
                new RawChatMessage(0, "😀", "x".repeat(2_047))));

        RawGameTextSnapshot over = snapshot(
                Observation.absent(), overScoreboard, overTab, overFooter, overLore, overChat);

        assertTrue(over.scoreboardLines().isUnknown());
        assertTrue(over.tabLines().isUnknown());
        assertTrue(over.tabFooter().isUnknown());
        assertTrue(over.vacuumLore().isUnknown());
        assertTrue(over.chatBatch().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT)),
                over.inputDiagnostics());
        assertProbeTraversals(overScoreboard, 8, 7);
        assertProbeTraversals(overTab, 8, 7);
        assertProbeTraversals(overFooter, 8, 7);
        assertProbeTraversals(overLore, 7, 6);
        assertProbeTraversals(overChat, 1, 0);
    }

    @Test
    void rejectsAListWhoseSizeChangesBeforeAnyIndexedRead() {
        ChangingSizeList<String> changing = new ChangingSizeList<>(1, 0);

        RawGameTextSnapshot snapshot = snapshotWithScoreboard(changing);

        assertTrue(snapshot.scoreboardLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
        assertEquals(0, changing.getCalls);
        assertEquals(-1, changing.highestIndex);
    }

    @Test
    void rejectsNegativeGenerationAndChatSequence() {
        assertThrows(IllegalArgumentException.class, () -> RawGameTextSnapshot.unknown(-1));
        assertThrows(IllegalArgumentException.class, () -> new RawChatMessage(-1, "message"));
        assertEquals(Long.MAX_VALUE, new RawChatMessage(Long.MAX_VALUE, "message").sequence());
    }

    private static RawGameTextSnapshot snapshotWithScoreboard(List<String> lines) {
        return snapshot(Observation.absent(), lines, List.of(), List.of(), List.of(), List.of());
    }

    private static RawGameTextSnapshot snapshotWithTab(List<String> lines) {
        return snapshot(Observation.absent(), List.of(), lines, List.of(), List.of(), List.of());
    }

    private static RawGameTextSnapshot snapshotWithFooter(List<String> lines) {
        return snapshot(Observation.absent(), List.of(), List.of(), lines, List.of(), List.of());
    }

    private static RawGameTextSnapshot snapshotWithLore(List<String> lines) {
        return snapshot(Observation.absent(), List.of(), List.of(), List.of(), lines, List.of());
    }

    private static RawGameTextSnapshot snapshotWithChat(List<RawChatMessage> messages) {
        return snapshot(Observation.absent(), List.of(), List.of(), List.of(), List.of(), messages);
    }

    private static RawGameTextSnapshot snapshot(
            Observation<String> title,
            List<String> scoreboard,
            List<String> tab,
            List<String> footer,
            List<String> lore,
            List<RawChatMessage> chat
    ) {
        return new RawGameTextSnapshot(
                title, Observation.present(scoreboard), Observation.present(tab),
                Observation.present(footer), Observation.present(lore), Observation.present(chat),
                PlayerFacts.unknown(), Observation.present(WorldTransition.STABLE), 1);
    }

    private static void assertProbeTraversals(ProbeList<?> probe, int getCalls, int highestIndex) {
        assertEquals(getCalls, probe.getCalls);
        assertEquals(0, probe.iteratorCalls);
        assertEquals(highestIndex, probe.highestIndex);
    }

    private static final class ProbeList<T> extends AbstractList<T> {
        private final List<T> values;
        private int getCalls;
        private int iteratorCalls;
        private int highestIndex = -1;

        private ProbeList(List<T> values) {
            this.values = values;
        }

        @Override
        public T get(int index) {
            getCalls++;
            highestIndex = Math.max(highestIndex, index);
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Iterator<T> iterator() {
            iteratorCalls++;
            return super.iterator();
        }
    }

    private static final class ChangingSizeList<T> extends AbstractList<T> {
        private final int firstSize;
        private final int laterSize;
        private int sizeCalls;
        private int getCalls;
        private int highestIndex = -1;

        private ChangingSizeList(int firstSize, int laterSize) {
            this.firstSize = firstSize;
            this.laterSize = laterSize;
        }

        @Override
        public T get(int index) {
            getCalls++;
            highestIndex = Math.max(highestIndex, index);
            throw new AssertionError("size change must be detected before indexed access");
        }

        @Override
        public int size() {
            return sizeCalls++ == 0 ? firstSize : laterSize;
        }
    }

    private static final class NeverReadHugeList<T> extends AbstractList<T> {
        private final int declaredSize;
        private int getCalls;

        private NeverReadHugeList(int declaredSize) {
            this.declaredSize = declaredSize;
        }

        @Override
        public T get(int index) {
            getCalls++;
            throw new AssertionError("an over-limit list must not be traversed");
        }

        @Override
        public int size() {
            return declaredSize;
        }
    }
}
