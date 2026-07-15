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
        List<String> maximumScoreboard = Collections.nCopies(
                GameTextInputBudget.MAX_SCOREBOARD_LINES, maximum);
        List<String> maximumTab = Collections.nCopies(
                lineCount - GameTextInputBudget.MAX_SCOREBOARD_LINES, maximum);
        RawGameTextSnapshot totalAtLimit = snapshot(
                Observation.absent(), maximumScoreboard, maximumTab,
                List.of(), List.of(), List.of());
        assertTrue(totalAtLimit.scoreboardLines().isPresent());
        assertTrue(totalAtLimit.tabLines().isPresent());
        assertTrue(totalAtLimit.inputDiagnostics().isEmpty());

        RawGameTextSnapshot totalOverLimit = snapshot(
                Observation.present("x"), maximumScoreboard, maximumTab,
                List.of(), List.of(), List.of());
        assertTrue(totalOverLimit.scoreboardTitle().isUnknown());
        assertTrue(totalOverLimit.scoreboardLines().isUnknown());
        assertTrue(totalOverLimit.tabLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT)),
                totalOverLimit.inputDiagnostics());
    }

    @Test
    void rejectsScoreboardPlusTabCounterexampleBeforeCopying() {
        String maximum = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS);
        ProbeList<String> scoreboard = new ProbeList<>(Collections.nCopies(
                GameTextInputBudget.MAX_SCOREBOARD_LINES, maximum));
        List<String> tabValues = new ArrayList<>(Collections.nCopies(
                Math.toIntExact(GameTextInputBudget.MAX_TOTAL_CHARACTERS
                        / GameTextInputBudget.MAX_LINE_CHARACTERS)
                        - GameTextInputBudget.MAX_SCOREBOARD_LINES,
                maximum));
        tabValues.add("x");
        ProbeList<String> tab = new ProbeList<>(tabValues);

        RawGameTextSnapshot snapshot = snapshot(
                Observation.absent(), scoreboard, tab, List.of(), List.of(), List.of());

        assertTrue(snapshot.scoreboardLines().isUnknown());
        assertTrue(snapshot.tabLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
        assertPreflightOnly(scoreboard, GameTextInputBudget.MAX_SCOREBOARD_LINES);
        assertPreflightOnly(tab, tabValues.size());
    }

    @Test
    void rejectsSameLengthStringReplacementAndReorderingBetweenPhases() {
        PhaseChangingList<String> replacement = new PhaseChangingList<>(
                List.of("safe"), List.of("evil"), List.of("later"));
        PhaseChangingList<String> reordered = new PhaseChangingList<>(
                List.of("aa", "bb"), List.of("bb", "aa"), List.of("cc", "dd"));

        RawGameTextSnapshot replaced = snapshotWithScoreboard(replacement);
        RawGameTextSnapshot swapped = snapshotWithTab(reordered);

        assertTrue(replaced.scoreboardLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)),
                replaced.inputDiagnostics());
        assertTrue(swapped.tabLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.tab.lines", ParseDiagnosticCode.INPUT_LIMIT)), swapped.inputDiagnostics());
        assertBoundedTwoPhaseTraversal(replacement, 1);
        assertBoundedTwoPhaseTraversal(reordered, 2);
    }

    @Test
    void rejectsSameSequenceSameLengthChatReplacementAndReorderingBetweenPhases() {
        RawChatMessage first = new RawChatMessage(7, "a", "bc");
        RawChatMessage second = new RawChatMessage(7, "b", "ac");
        RawChatMessage changed = new RawChatMessage(7, "c", "ab");
        PhaseChangingList<RawChatMessage> replacement = new PhaseChangingList<>(
                List.of(first), List.of(changed), List.of(second));
        PhaseChangingList<RawChatMessage> reordered = new PhaseChangingList<>(
                List.of(first, second), List.of(second, first), List.of(changed, changed));

        RawGameTextSnapshot replaced = snapshotWithChat(replacement);
        RawGameTextSnapshot swapped = snapshotWithChat(reordered);

        assertTrue(replaced.chatBatch().isUnknown());
        assertTrue(swapped.chatBatch().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT)), replaced.inputDiagnostics());
        assertEquals(List.of(new ParseDiagnostic(
                "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT)), swapped.inputDiagnostics());
        assertBoundedTwoPhaseTraversal(replacement, 1);
        assertBoundedTwoPhaseTraversal(reordered, 2);
    }

    @Test
    void sealsTheVerifiedValuesWithoutAThirdExternalTraversal() {
        PhaseChangingList<String> strings = new PhaseChangingList<>(
                List.of("one", "two"), List.of("one", "two"), List.of("bad", "bad"));
        RawChatMessage chat = new RawChatMessage(9, "system", "safe");
        RawChatMessage later = new RawChatMessage(9, "system", "evil");
        PhaseChangingList<RawChatMessage> messages = new PhaseChangingList<>(
                List.of(chat), List.of(chat), List.of(later));

        RawGameTextSnapshot snapshot = snapshot(
                Observation.absent(), strings, List.of(), List.of(), List.of(), messages);

        assertEquals(List.of("one", "two"), snapshot.scoreboardLines().get());
        assertEquals(List.of(chat), snapshot.chatBatch().get());
        assertEquals("bad", strings.get(0));
        assertEquals(later, messages.get(0));
        assertEquals(List.of("one", "two"), snapshot.scoreboardLines().get());
        assertEquals(List.of(chat), snapshot.chatBatch().get());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.scoreboardLines().get().add("mutate"));
    }

    @Test
    void containsSecondPhaseSizeFailuresAndNullValues() {
        ThrowingLaterSizeList<String> throwingSize = new ThrowingLaterSizeList<>(List.of("safe"));
        PhaseChangingList<String> nullReplacement = new PhaseChangingList<>(
                List.of("safe"), Collections.singletonList(null), List.of("later"));

        assertRejectedScoreboard(throwingSize);
        assertRejectedScoreboard(nullReplacement);
        assertTrue(throwingSize.getCalls <= 1);
        assertBoundedTwoPhaseTraversal(nullReplacement, 1);
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
        assertBoundedLegalTraversal(exactScoreboard, 8);
        assertBoundedLegalTraversal(exactTab, 8);
        assertBoundedLegalTraversal(exactFooter, 8);
        assertBoundedLegalTraversal(exactLore, 7);
        assertBoundedLegalTraversal(exactChat, 1);

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
        assertPreflightOnly(overScoreboard, 8);
        assertPreflightOnly(overTab, 8);
        assertPreflightOnly(overFooter, 8);
        assertPreflightOnly(overLore, 7);
        assertPreflightOnly(overChat, 1);
    }

    @Test
    void rejectsAListWhoseSizeChangesBeforeAnyIndexedRead() {
        ChangingSizeList<String> changing = new ChangingSizeList<>(1, 0, "safe");

        RawGameTextSnapshot snapshot = snapshotWithScoreboard(changing);

        assertTrue(snapshot.scoreboardLines().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
        assertTrue(changing.getCalls <= 1);
        assertTrue(changing.highestIndex <= 0);
    }

    @Test
    void containsEveryHostileStringListCallbackAtTheFieldBoundary() {
        assertRejectedScoreboard(new ThrowingSizeList<>(
                new IllegalStateException("private size detail")));
        assertRejectedScoreboard(new ThrowingGetList<>(
                List.of("safe"), 1, new AssertionError("private preflight detail")));
        assertRejectedScoreboard(new ThrowingGetList<>(
                List.of("safe"), 2, new IllegalArgumentException("private copy detail")));
        assertRejectedScoreboard(new ThrowingGetList<>(
                List.of("safe"), 2, new AssertionError("private copy error detail")));
    }

    @Test
    void containsEveryHostileChatListCallbackAtTheBatchBoundary() {
        RawChatMessage message = new RawChatMessage(0, "safe");
        assertRejectedChat(new ThrowingSizeList<>(
                new IllegalStateException("private chat size detail")));
        assertRejectedChat(new ThrowingGetList<>(
                List.of(message), 1, new AssertionError("private chat preflight detail")));
        assertRejectedChat(new ThrowingGetList<>(
                List.of(message), 2, new IllegalArgumentException("private chat copy detail")));
        assertRejectedChat(new ThrowingGetList<>(
                List.of(message), 2, new AssertionError("private chat copy error detail")));
    }

    @Test
    void rejectsNullElementsAndChangingSizesWithoutPollutingLegalFields() {
        List<String> nullString = new ArrayList<>();
        nullString.add(null);
        RawGameTextSnapshot stringSnapshot = snapshot(
                Observation.present("SKYBLOCK"), nullString, List.of("Area: Garden"),
                List.of(), List.of(), List.of());
        assertTrue(stringSnapshot.scoreboardTitle().isPresent());
        assertTrue(stringSnapshot.scoreboardLines().isUnknown());
        assertEquals(List.of("Area: Garden"), stringSnapshot.tabLines().get());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)),
                stringSnapshot.inputDiagnostics());

        List<RawChatMessage> nullChat = new ArrayList<>();
        nullChat.add(null);
        assertRejectedChat(nullChat);
        assertRejectedChat(new ChangingSizeList<>(
                1, 0, new RawChatMessage(0, "safe")));
    }

    @Test
    void hostileResultsAndDiagnosticsRemainDeeplyImmutableAndPrivate() {
        RawGameTextSnapshot hostile = snapshot(
                Observation.present("SKYBLOCK"),
                new ThrowingGetList<>(List.of("secret"), 2,
                        new AssertionError("do not expose this exception text")),
                List.of("Area: Garden"), List.of(), List.of(), List.of());

        assertTrue(hostile.scoreboardLines().isUnknown());
        assertEquals(List.of("Area: Garden"), hostile.tabLines().get());
        assertThrows(UnsupportedOperationException.class,
                () -> hostile.tabLines().get().add("mutate"));
        assertThrows(UnsupportedOperationException.class,
                () -> hostile.inputDiagnostics().add(new ParseDiagnostic(
                        "mutate", ParseDiagnosticCode.INPUT_LIMIT)));
        assertTrue(hostile.inputDiagnostics().toString().contains("input.scoreboard.lines"));
        assertTrue(!hostile.inputDiagnostics().toString().contains("do not expose"));
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

    private static void assertRejectedScoreboard(List<String> hostile) {
        RawGameTextSnapshot snapshot = snapshot(
                Observation.present("SKYBLOCK"), hostile, List.of("Area: Garden"),
                List.of(), List.of(), List.of());
        assertTrue(snapshot.scoreboardTitle().isPresent());
        assertTrue(snapshot.scoreboardLines().isUnknown());
        assertEquals(List.of("Area: Garden"), snapshot.tabLines().get());
        assertEquals(List.of(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
    }

    private static void assertRejectedChat(List<RawChatMessage> hostile) {
        RawGameTextSnapshot snapshot = snapshot(
                Observation.present("SKYBLOCK"), List.of("Purse: 0"),
                List.of("Area: Garden"), List.of(), List.of(), hostile);
        assertTrue(snapshot.scoreboardTitle().isPresent());
        assertEquals(List.of("Purse: 0"), snapshot.scoreboardLines().get());
        assertEquals(List.of("Area: Garden"), snapshot.tabLines().get());
        assertTrue(snapshot.chatBatch().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT)),
                snapshot.inputDiagnostics());
    }

    private static void assertPreflightOnly(ProbeList<?> probe, int elementCount) {
        assertTrue(probe.getCalls <= elementCount,
                () -> "preflight read too many elements: " + probe.getCalls);
        assertTrue(probe.iteratorCalls <= 1,
                () -> "preflight created too many iterators: " + probe.iteratorCalls);
        assertTrue(probe.highestIndex < elementCount,
                () -> "out-of-bounds preflight index: " + probe.highestIndex);
    }

    private static void assertBoundedLegalTraversal(ProbeList<?> probe, int elementCount) {
        assertTrue(probe.getCalls > 0);
        assertTrue(probe.getCalls <= elementCount * 2,
                () -> "legal path read too many elements: " + probe.getCalls);
        assertTrue(probe.iteratorCalls <= 2,
                () -> "legal path created too many iterators: " + probe.iteratorCalls);
        assertTrue(probe.highestIndex < elementCount,
                () -> "out-of-bounds legal index: " + probe.highestIndex);
    }

    private static void assertBoundedTwoPhaseTraversal(
            PhaseChangingList<?> probe,
            int elementCount
    ) {
        assertTrue(probe.getCalls > 0);
        assertTrue(probe.getCalls <= elementCount * 2,
                () -> "two-phase validation read too many elements: " + probe.getCalls);
        assertTrue(probe.highestIndex < elementCount,
                () -> "out-of-bounds two-phase index: " + probe.highestIndex);
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
        private final T value;
        private int sizeCalls;
        private int getCalls;
        private int highestIndex = -1;

        private ChangingSizeList(int firstSize, int laterSize, T value) {
            this.firstSize = firstSize;
            this.laterSize = laterSize;
            this.value = value;
        }

        @Override
        public T get(int index) {
            getCalls++;
            highestIndex = Math.max(highestIndex, index);
            return value;
        }

        @Override
        public int size() {
            return sizeCalls++ == 0 ? firstSize : laterSize;
        }
    }

    private static final class PhaseChangingList<T> extends AbstractList<T> {
        private final List<T> preflightValues;
        private final List<T> verificationValues;
        private final List<T> laterValues;
        private int getCalls;
        private int highestIndex = -1;

        private PhaseChangingList(
                List<T> preflightValues,
                List<T> verificationValues,
                List<T> laterValues
        ) {
            assertEquals(preflightValues.size(), verificationValues.size());
            assertEquals(preflightValues.size(), laterValues.size());
            this.preflightValues = preflightValues;
            this.verificationValues = verificationValues;
            this.laterValues = laterValues;
        }

        @Override
        public T get(int index) {
            highestIndex = Math.max(highestIndex, index);
            List<T> values;
            if (getCalls < preflightValues.size()) {
                values = preflightValues;
            } else if (getCalls < preflightValues.size() * 2) {
                values = verificationValues;
            } else {
                values = laterValues;
            }
            getCalls++;
            return values.get(index);
        }

        @Override
        public int size() {
            return preflightValues.size();
        }
    }

    private static final class ThrowingLaterSizeList<T> extends AbstractList<T> {
        private final List<T> values;
        private int sizeCalls;
        private int getCalls;

        private ThrowingLaterSizeList(List<T> values) {
            this.values = values;
        }

        @Override
        public T get(int index) {
            getCalls++;
            return values.get(index);
        }

        @Override
        public int size() {
            if (sizeCalls++ > 0) {
                throw new AssertionError("private second-phase size detail");
            }
            return values.size();
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

    private static final class ThrowingSizeList<T> extends AbstractList<T> {
        private final Throwable failure;

        private ThrowingSizeList(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("an unavailable size must prevent element access");
        }

        @Override
        public int size() {
            throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
    }

    private static final class ThrowingGetList<T> extends AbstractList<T> {
        private final List<T> values;
        private final int failingCall;
        private final Throwable failure;
        private int getCalls;

        private ThrowingGetList(List<T> values, int failingCall, Throwable failure) {
            this.values = values;
            this.failingCall = failingCall;
            this.failure = failure;
        }

        @Override
        public T get(int index) {
            getCalls++;
            if (getCalls == failingCall) {
                throwUnchecked(failure);
            }
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}
