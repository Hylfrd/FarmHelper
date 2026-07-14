package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A deeply immutable, input-budgeted batch of already-collected game text and parser facts. */
public final class RawGameTextSnapshot {
    private final Observation<String> scoreboardTitle;
    private final Observation<List<String>> scoreboardLines;
    private final Observation<List<String>> tabLines;
    private final Observation<List<String>> tabFooter;
    private final Observation<List<String>> vacuumLore;
    private final Observation<List<RawChatMessage>> chatBatch;
    private final PlayerFacts playerFacts;
    private final Observation<WorldTransition> worldTransition;
    private final long generation;
    private final List<ParseDiagnostic> inputDiagnostics;

    public RawGameTextSnapshot(
            Observation<String> scoreboardTitle,
            Observation<List<String>> scoreboardLines,
            Observation<List<String>> tabLines,
            Observation<List<String>> tabFooter,
            Observation<List<String>> vacuumLore,
            Observation<List<RawChatMessage>> chatBatch,
            PlayerFacts playerFacts,
            Observation<WorldTransition> worldTransition,
            long generation
    ) {
        Objects.requireNonNull(scoreboardTitle, "scoreboardTitle");
        Objects.requireNonNull(scoreboardLines, "scoreboardLines");
        Objects.requireNonNull(tabLines, "tabLines");
        Objects.requireNonNull(tabFooter, "tabFooter");
        Objects.requireNonNull(vacuumLore, "vacuumLore");
        Objects.requireNonNull(chatBatch, "chatBatch");
        this.playerFacts = Objects.requireNonNull(playerFacts, "playerFacts");
        this.worldTransition = Objects.requireNonNull(worldTransition, "worldTransition");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;

        int scoreboardSize = observedSize(scoreboardLines);
        int tabSize = observedSize(tabLines);
        int footerSize = observedSize(tabFooter);
        int loreSize = observedSize(vacuumLore);
        int chatSize = observedSize(chatBatch);

        long total = 0L;
        long titleCharacters = preflightTitleCharacters(scoreboardTitle);
        long scoreboardCharacters = -1L;
        long tabCharacters = -1L;
        long footerCharacters = -1L;
        long loreCharacters = -1L;
        long chatCharacters = -1L;

        total = addPreflightCharacters(total, titleCharacters);
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            scoreboardCharacters = preflightStringCharacters(
                    scoreboardLines, GameTextInputBudget.MAX_SCOREBOARD_LINES, scoreboardSize);
            total = addPreflightCharacters(total, scoreboardCharacters);
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            tabCharacters = preflightStringCharacters(
                    tabLines, GameTextInputBudget.MAX_TAB_LINES, tabSize);
            total = addPreflightCharacters(total, tabCharacters);
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            footerCharacters = preflightStringCharacters(
                    tabFooter, GameTextInputBudget.MAX_TAB_FOOTER_LINES, footerSize);
            total = addPreflightCharacters(total, footerCharacters);
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            loreCharacters = preflightStringCharacters(
                    vacuumLore, GameTextInputBudget.MAX_VACUUM_LORE_LINES, loreSize);
            total = addPreflightCharacters(total, loreCharacters);
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            chatCharacters = preflightChatCharacters(chatBatch, chatSize);
            total = addPreflightCharacters(total, chatCharacters);
        }
        if (total > GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            this.scoreboardTitle = unknownIfPresent(scoreboardTitle);
            this.scoreboardLines = unknownIfPresent(scoreboardLines);
            this.tabLines = unknownIfPresent(tabLines);
            this.tabFooter = unknownIfPresent(tabFooter);
            this.vacuumLore = unknownIfPresent(vacuumLore);
            this.chatBatch = unknownIfPresent(chatBatch);
            this.inputDiagnostics = List.of(
                    new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT));
            return;
        }

        List<ParseDiagnostic> diagnostics = new ArrayList<>();
        Checked<String> checkedTitle = checkTitle(scoreboardTitle, diagnostics);
        Checked<List<String>> checkedScoreboard = checkStrings(
                scoreboardLines, GameTextInputBudget.MAX_SCOREBOARD_LINES,
                scoreboardSize, scoreboardCharacters, "input.scoreboard.lines", diagnostics);
        Checked<List<String>> checkedTab = checkStrings(
                tabLines, GameTextInputBudget.MAX_TAB_LINES,
                tabSize, tabCharacters, "input.tab.lines", diagnostics);
        Checked<List<String>> checkedFooter = checkStrings(
                tabFooter, GameTextInputBudget.MAX_TAB_FOOTER_LINES,
                footerSize, footerCharacters, "input.tab.footer", diagnostics);
        Checked<List<String>> checkedLore = checkStrings(
                vacuumLore, GameTextInputBudget.MAX_VACUUM_LORE_LINES,
                loreSize, loreCharacters, "input.vacuum.lore", diagnostics);
        Checked<List<RawChatMessage>> checkedChat = checkChat(
                chatBatch, chatSize, chatCharacters, diagnostics);

        this.scoreboardTitle = checkedTitle.value();
        this.scoreboardLines = checkedScoreboard.value();
        this.tabLines = checkedTab.value();
        this.tabFooter = checkedFooter.value();
        this.vacuumLore = checkedLore.value();
        this.chatBatch = checkedChat.value();
        this.inputDiagnostics = List.copyOf(diagnostics);
    }

    private static int observedSize(Observation<? extends List<?>> source) {
        if (!source.isPresent()) {
            return -1;
        }
        ExternalCall<Integer> size = externalSize(source.get());
        return size.succeeded() ? size.value() : -1;
    }

    private static long preflightTitleCharacters(Observation<String> source) {
        if (!source.isPresent()) {
            return 0L;
        }
        String title = Objects.requireNonNull(source.get(), "scoreboardTitle value");
        return title.length() <= GameTextInputBudget.MAX_LINE_CHARACTERS ? title.length() : -1L;
    }

    private static long preflightStringCharacters(
            Observation<List<String>> source,
            int maximumLines,
            int expectedSize
    ) {
        if (!source.isPresent()) {
            return 0L;
        }
        if (expectedSize < 0 || expectedSize > maximumLines) {
            return -1L;
        }
        List<String> lines = source.get();
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            if (!hasExpectedSize(lines, expectedSize)) {
                return -1L;
            }
            ExternalCall<String> lineCall = externalGet(lines, index);
            String line = lineCall.value();
            if (!lineCall.succeeded() || line == null
                    || line.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                return -1L;
            }
            characters = Math.addExact(characters, (long) line.length());
        }
        return hasExpectedSize(lines, expectedSize) ? characters : -1L;
    }

    private static long preflightChatCharacters(
            Observation<List<RawChatMessage>> source,
            int expectedSize
    ) {
        if (!source.isPresent()) {
            return 0L;
        }
        if (expectedSize < 0 || expectedSize > GameTextInputBudget.MAX_CHAT_MESSAGES) {
            return -1L;
        }
        List<RawChatMessage> messages = source.get();
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            if (!hasExpectedSize(messages, expectedSize)) {
                return -1L;
            }
            ExternalCall<RawChatMessage> messageCall = externalGet(messages, index);
            RawChatMessage message = messageCall.value();
            if (!messageCall.succeeded() || message == null
                    || message.channel().length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                    || message.text().length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                return -1L;
            }
            characters = Math.addExact(characters, (long) message.channel().length());
            characters = Math.addExact(characters, (long) message.text().length());
        }
        return hasExpectedSize(messages, expectedSize) ? characters : -1L;
    }

    private static long addPreflightCharacters(long total, long characters) {
        if (characters < 0L) {
            return total;
        }
        return Math.addExact(total, characters);
    }

    private static Checked<String> checkTitle(
            Observation<String> source,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source, 0L);
        }
        String title = Objects.requireNonNull(source.get(), "scoreboardTitle value");
        if (title.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
            diagnostics.add(new ParseDiagnostic("input.scoreboard.title", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        return new Checked<>(source, title.length());
    }

    private static Checked<List<String>> checkStrings(
            Observation<List<String>> source,
            int maximumLines,
            int expectedSize,
            long expectedCharacters,
            String field,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source, 0L);
        }
        List<String> lines = source.get();
        ExternalCall<Integer> initialSize = externalSize(lines);
        if (expectedSize < 0 || expectedSize > maximumLines || expectedCharacters < 0L
                || !initialSize.succeeded() || initialSize.value() != expectedSize) {
            diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        List<String> copy = new ArrayList<>(expectedSize);
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            if (!hasExpectedSize(lines, expectedSize)) {
                diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            ExternalCall<String> lineCall = externalGet(lines, index);
            String line = lineCall.value();
            if (!lineCall.succeeded() || line == null
                    || line.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            characters = Math.addExact(characters, (long) line.length());
            copy.add(line);
        }
        if (!hasExpectedSize(lines, expectedSize) || characters != expectedCharacters) {
            diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        return new Checked<>(Observation.present(List.copyOf(copy)), characters);
    }

    private static Checked<List<RawChatMessage>> checkChat(
            Observation<List<RawChatMessage>> source,
            int expectedSize,
            long expectedCharacters,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source, 0L);
        }
        List<RawChatMessage> messages = source.get();
        ExternalCall<Integer> initialSize = externalSize(messages);
        if (expectedSize < 0 || expectedSize > GameTextInputBudget.MAX_CHAT_MESSAGES
                || expectedCharacters < 0L || !initialSize.succeeded()
                || initialSize.value() != expectedSize) {
            diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        List<RawChatMessage> copy = new ArrayList<>(expectedSize);
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            if (!hasExpectedSize(messages, expectedSize)) {
                diagnostics.add(new ParseDiagnostic(
                        "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            ExternalCall<RawChatMessage> messageCall = externalGet(messages, index);
            RawChatMessage message = messageCall.value();
            if (!messageCall.succeeded() || message == null
                    || message.channel().length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                    || message.text().length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                diagnostics.add(new ParseDiagnostic(
                        "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            characters = Math.addExact(characters, (long) message.channel().length());
            characters = Math.addExact(characters, (long) message.text().length());
            copy.add(message);
        }
        if (!hasExpectedSize(messages, expectedSize) || characters != expectedCharacters) {
            diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        return new Checked<>(Observation.present(List.copyOf(copy)), characters);
    }

    private static boolean hasExpectedSize(List<?> source, int expectedSize) {
        ExternalCall<Integer> size = externalSize(source);
        return size.succeeded() && size.value() == expectedSize;
    }

    /** The try block contains only the untrusted collection callback. */
    private static ExternalCall<Integer> externalSize(List<?> source) {
        if (source == null) {
            return ExternalCall.failed();
        }
        int value;
        try {
            value = source.size();
        } catch (RuntimeException | Error ignored) {
            return ExternalCall.failed();
        }
        return ExternalCall.succeeded(value);
    }

    /** The try block contains only the untrusted collection callback. */
    private static <T> ExternalCall<T> externalGet(List<T> source, int index) {
        if (source == null) {
            return ExternalCall.failed();
        }
        T value;
        try {
            value = source.get(index);
        } catch (RuntimeException | Error ignored) {
            return ExternalCall.failed();
        }
        return ExternalCall.succeeded(value);
    }

    private static <T> Observation<T> unknownIfPresent(Observation<T> source) {
        return source.isPresent() ? Observation.unknown() : source;
    }

    public Observation<String> scoreboardTitle() {
        return scoreboardTitle;
    }

    public Observation<List<String>> scoreboardLines() {
        return scoreboardLines;
    }

    public Observation<List<String>> tabLines() {
        return tabLines;
    }

    public Observation<List<String>> tabFooter() {
        return tabFooter;
    }

    public Observation<List<String>> vacuumLore() {
        return vacuumLore;
    }

    public Observation<List<RawChatMessage>> chatBatch() {
        return chatBatch;
    }

    public PlayerFacts playerFacts() {
        return playerFacts;
    }

    public Observation<WorldTransition> worldTransition() {
        return worldTransition;
    }

    public long generation() {
        return generation;
    }

    List<ParseDiagnostic> inputDiagnostics() {
        return inputDiagnostics;
    }

    public static RawGameTextSnapshot unknown(long generation) {
        return new RawGameTextSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                PlayerFacts.unknown(), Observation.unknown(), generation);
    }

    private record Checked<T>(Observation<T> value, long characters) {
    }

    private record ExternalCall<T>(boolean succeeded, T value) {
        private static <T> ExternalCall<T> succeeded(T value) {
            return new ExternalCall<>(true, value);
        }

        private static <T> ExternalCall<T> failed() {
            return new ExternalCall<>(false, null);
        }
    }
}
