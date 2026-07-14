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

        // Exact-size staging stores references only after these public caps succeed. Its static
        // maximum is 128 + 256 + 128 + 64 + 256 = 832 references across all five collections.
        int scoreboardSize = boundedObservedSize(
                scoreboardLines, GameTextInputBudget.MAX_SCOREBOARD_LINES);
        int tabSize = boundedObservedSize(tabLines, GameTextInputBudget.MAX_TAB_LINES);
        int footerSize = boundedObservedSize(
                tabFooter, GameTextInputBudget.MAX_TAB_FOOTER_LINES);
        int loreSize = boundedObservedSize(
                vacuumLore, GameTextInputBudget.MAX_VACUUM_LORE_LINES);
        int chatSize = boundedObservedSize(chatBatch, GameTextInputBudget.MAX_CHAT_MESSAGES);

        long total = 0L;
        long titleCharacters = preflightTitleCharacters(scoreboardTitle);
        StringPreflight scoreboardPreflight = StringPreflight.invalid();
        StringPreflight tabPreflight = StringPreflight.invalid();
        StringPreflight footerPreflight = StringPreflight.invalid();
        StringPreflight lorePreflight = StringPreflight.invalid();
        ChatPreflight chatPreflight = ChatPreflight.invalid();

        total = addPreflightCharacters(total, titleCharacters);
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            scoreboardPreflight = preflightStrings(scoreboardLines, scoreboardSize);
            total = addPreflightCharacters(total, scoreboardPreflight.characters());
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            tabPreflight = preflightStrings(tabLines, tabSize);
            total = addPreflightCharacters(total, tabPreflight.characters());
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            footerPreflight = preflightStrings(tabFooter, footerSize);
            total = addPreflightCharacters(total, footerPreflight.characters());
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            lorePreflight = preflightStrings(vacuumLore, loreSize);
            total = addPreflightCharacters(total, lorePreflight.characters());
        }
        if (total <= GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
            chatPreflight = preflightChat(chatBatch, chatSize);
            total = addPreflightCharacters(total, chatPreflight.characters());
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
                scoreboardPreflight, "input.scoreboard.lines", diagnostics);
        Checked<List<String>> checkedTab = checkStrings(
                tabLines, GameTextInputBudget.MAX_TAB_LINES,
                tabPreflight, "input.tab.lines", diagnostics);
        Checked<List<String>> checkedFooter = checkStrings(
                tabFooter, GameTextInputBudget.MAX_TAB_FOOTER_LINES,
                footerPreflight, "input.tab.footer", diagnostics);
        Checked<List<String>> checkedLore = checkStrings(
                vacuumLore, GameTextInputBudget.MAX_VACUUM_LORE_LINES,
                lorePreflight, "input.vacuum.lore", diagnostics);
        Checked<List<RawChatMessage>> checkedChat = checkChat(
                chatBatch, chatPreflight, diagnostics);

        this.scoreboardTitle = checkedTitle.value();
        this.scoreboardLines = checkedScoreboard.value();
        this.tabLines = checkedTab.value();
        this.tabFooter = checkedFooter.value();
        this.vacuumLore = checkedLore.value();
        this.chatBatch = checkedChat.value();
        this.inputDiagnostics = List.copyOf(diagnostics);
    }

    private static int boundedObservedSize(
            Observation<? extends List<?>> source,
            int maximumSize
    ) {
        if (!source.isPresent()) {
            return -1;
        }
        ExternalCall<Integer> size = externalSize(source.get());
        if (!size.succeeded() || size.value() < 0 || size.value() > maximumSize) {
            return -1;
        }
        return size.value();
    }

    private static long preflightTitleCharacters(Observation<String> source) {
        if (!source.isPresent()) {
            return 0L;
        }
        String title = Objects.requireNonNull(source.get(), "scoreboardTitle value");
        return title.length() <= GameTextInputBudget.MAX_LINE_CHARACTERS ? title.length() : -1L;
    }

    private static StringPreflight preflightStrings(
            Observation<List<String>> source,
            int expectedSize
    ) {
        if (!source.isPresent()) {
            return StringPreflight.notPresent();
        }
        if (expectedSize < 0) {
            return StringPreflight.invalid();
        }
        List<String> lines = source.get();
        String[] stagedValues = new String[expectedSize];
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            ExternalCall<String> lineCall = externalGet(lines, index);
            String line = lineCall.value();
            if (!lineCall.succeeded() || line == null
                    || line.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                return StringPreflight.invalid();
            }
            stagedValues[index] = line;
            characters = Math.addExact(characters, (long) line.length());
        }
        return new StringPreflight(expectedSize, characters, stagedValues);
    }

    private static ChatPreflight preflightChat(
            Observation<List<RawChatMessage>> source,
            int expectedSize
    ) {
        if (!source.isPresent()) {
            return ChatPreflight.notPresent();
        }
        if (expectedSize < 0) {
            return ChatPreflight.invalid();
        }
        List<RawChatMessage> messages = source.get();
        RawChatMessage[] stagedValues = new RawChatMessage[expectedSize];
        long characters = 0L;
        for (int index = 0; index < expectedSize; index++) {
            ExternalCall<RawChatMessage> messageCall = externalGet(messages, index);
            RawChatMessage message = messageCall.value();
            if (!messageCall.succeeded() || message == null
                    || message.channel().length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                    || message.text().length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                return ChatPreflight.invalid();
            }
            stagedValues[index] = message;
            characters = Math.addExact(characters, (long) message.channel().length());
            characters = Math.addExact(characters, (long) message.text().length());
        }
        return new ChatPreflight(expectedSize, characters, stagedValues);
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
            return new Checked<>(source);
        }
        String title = Objects.requireNonNull(source.get(), "scoreboardTitle value");
        if (title.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
            diagnostics.add(new ParseDiagnostic("input.scoreboard.title", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown());
        }
        return new Checked<>(source);
    }

    private static Checked<List<String>> checkStrings(
            Observation<List<String>> source,
            int maximumLines,
            StringPreflight preflight,
            String field,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source);
        }
        List<String> lines = source.get();
        int expectedSize = preflight.expectedSize();
        ExternalCall<Integer> initialSize = externalSize(lines);
        if (!preflight.valid() || expectedSize < 0 || expectedSize > maximumLines
                || !initialSize.succeeded() || initialSize.value() != expectedSize) {
            diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown());
        }
        for (int index = 0; index < expectedSize; index++) {
            ExternalCall<String> lineCall = externalGet(lines, index);
            if (!lineCall.succeeded()
                    || !Objects.equals(preflight.values()[index], lineCall.value())) {
                diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown());
            }
        }
        if (!hasExpectedSize(lines, expectedSize)) {
            diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown());
        }
        return new Checked<>(Observation.present(List.of(preflight.values())));
    }

    private static Checked<List<RawChatMessage>> checkChat(
            Observation<List<RawChatMessage>> source,
            ChatPreflight preflight,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source);
        }
        List<RawChatMessage> messages = source.get();
        int expectedSize = preflight.expectedSize();
        ExternalCall<Integer> initialSize = externalSize(messages);
        if (!preflight.valid() || expectedSize < 0
                || expectedSize > GameTextInputBudget.MAX_CHAT_MESSAGES || !initialSize.succeeded()
                || initialSize.value() != expectedSize) {
            diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown());
        }
        for (int index = 0; index < expectedSize; index++) {
            ExternalCall<RawChatMessage> messageCall = externalGet(messages, index);
            if (!messageCall.succeeded()
                    || !Objects.equals(preflight.values()[index], messageCall.value())) {
                diagnostics.add(new ParseDiagnostic(
                        "input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown());
            }
        }
        if (!hasExpectedSize(messages, expectedSize)) {
            diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown());
        }
        return new Checked<>(Observation.present(List.of(preflight.values())));
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

    private record Checked<T>(Observation<T> value) {
    }

    private record StringPreflight(int expectedSize, long characters, String[] values) {
        private static StringPreflight notPresent() {
            return new StringPreflight(-1, 0L, null);
        }

        private static StringPreflight invalid() {
            return new StringPreflight(-1, -1L, null);
        }

        private boolean valid() {
            return values != null;
        }
    }

    private record ChatPreflight(
            int expectedSize,
            long characters,
            RawChatMessage[] values
    ) {
        private static ChatPreflight notPresent() {
            return new ChatPreflight(-1, 0L, null);
        }

        private static ChatPreflight invalid() {
            return new ChatPreflight(-1, -1L, null);
        }

        private boolean valid() {
            return values != null;
        }
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
