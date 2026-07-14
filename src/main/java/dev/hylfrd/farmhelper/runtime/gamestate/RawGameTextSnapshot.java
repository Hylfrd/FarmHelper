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

        List<ParseDiagnostic> diagnostics = new ArrayList<>();
        Checked<String> checkedTitle = checkTitle(scoreboardTitle, diagnostics);
        Checked<List<String>> checkedScoreboard = checkStrings(
                scoreboardLines, GameTextInputBudget.MAX_SCOREBOARD_LINES,
                "input.scoreboard.lines", diagnostics);
        Checked<List<String>> checkedTab = checkStrings(
                tabLines, GameTextInputBudget.MAX_TAB_LINES, "input.tab.lines", diagnostics);
        Checked<List<String>> checkedFooter = checkStrings(
                tabFooter, GameTextInputBudget.MAX_TAB_FOOTER_LINES,
                "input.tab.footer", diagnostics);
        Checked<List<String>> checkedLore = checkStrings(
                vacuumLore, GameTextInputBudget.MAX_VACUUM_LORE_LINES,
                "input.vacuum.lore", diagnostics);
        Checked<List<RawChatMessage>> checkedChat = checkChat(chatBatch, diagnostics);

        long total = 0L;
        boolean totalExceeded = false;
        for (Checked<?> checked : List.of(
                checkedTitle, checkedScoreboard, checkedTab, checkedFooter, checkedLore, checkedChat)) {
            try {
                total = Math.addExact(total, checked.characters());
            } catch (ArithmeticException exception) {
                totalExceeded = true;
                break;
            }
            if (total > GameTextInputBudget.MAX_TOTAL_CHARACTERS) {
                totalExceeded = true;
                break;
            }
        }
        if (totalExceeded) {
            diagnostics.add(new ParseDiagnostic("input.total", ParseDiagnosticCode.INPUT_LIMIT));
            this.scoreboardTitle = unknownIfPresent(scoreboardTitle);
            this.scoreboardLines = unknownIfPresent(scoreboardLines);
            this.tabLines = unknownIfPresent(tabLines);
            this.tabFooter = unknownIfPresent(tabFooter);
            this.vacuumLore = unknownIfPresent(vacuumLore);
            this.chatBatch = unknownIfPresent(chatBatch);
        } else {
            this.scoreboardTitle = checkedTitle.value();
            this.scoreboardLines = checkedScoreboard.value();
            this.tabLines = checkedTab.value();
            this.tabFooter = checkedFooter.value();
            this.vacuumLore = checkedLore.value();
            this.chatBatch = checkedChat.value();
        }
        this.inputDiagnostics = List.copyOf(diagnostics);
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
            String field,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source, 0L);
        }
        List<String> lines = Objects.requireNonNull(source.get(), "lines");
        int initialSize = lines.size();
        if (initialSize > maximumLines) {
            diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        List<String> copy = new ArrayList<>(initialSize);
        long characters = 0L;
        for (String line : lines) {
            if (copy.size() == maximumLines) {
                diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            Objects.requireNonNull(line, "line");
            if (line.length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                diagnostics.add(new ParseDiagnostic(field, ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            characters = Math.addExact(characters, (long) line.length());
            copy.add(line);
        }
        return new Checked<>(Observation.present(List.copyOf(copy)), characters);
    }

    private static Checked<List<RawChatMessage>> checkChat(
            Observation<List<RawChatMessage>> source,
            List<ParseDiagnostic> diagnostics
    ) {
        if (!source.isPresent()) {
            return new Checked<>(source, 0L);
        }
        List<RawChatMessage> messages = Objects.requireNonNull(source.get(), "chatBatch value");
        int initialSize = messages.size();
        if (initialSize > GameTextInputBudget.MAX_CHAT_MESSAGES) {
            diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
            return new Checked<>(Observation.unknown(), 0L);
        }
        List<RawChatMessage> copy = new ArrayList<>(initialSize);
        long characters = 0L;
        for (RawChatMessage message : messages) {
            if (copy.size() == GameTextInputBudget.MAX_CHAT_MESSAGES) {
                diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            Objects.requireNonNull(message, "message");
            if (message.channel().length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                    || message.text().length() > GameTextInputBudget.MAX_LINE_CHARACTERS) {
                diagnostics.add(new ParseDiagnostic("input.chat.batch", ParseDiagnosticCode.INPUT_LIMIT));
                return new Checked<>(Observation.unknown(), 0L);
            }
            characters = Math.addExact(characters, (long) message.channel().length());
            characters = Math.addExact(characters, (long) message.text().length());
            copy.add(message);
        }
        return new Checked<>(Observation.present(List.copyOf(copy)), characters);
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
}
