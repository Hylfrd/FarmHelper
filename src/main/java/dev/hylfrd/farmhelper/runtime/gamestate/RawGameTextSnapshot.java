package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.List;
import java.util.Objects;

/** A deeply immutable batch of already-collected game text and parser facts. */
public record RawGameTextSnapshot(
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
    public RawGameTextSnapshot {
        Objects.requireNonNull(scoreboardTitle, "scoreboardTitle");
        Objects.requireNonNull(scoreboardLines, "scoreboardLines");
        Objects.requireNonNull(tabLines, "tabLines");
        Objects.requireNonNull(tabFooter, "tabFooter");
        Objects.requireNonNull(vacuumLore, "vacuumLore");
        Objects.requireNonNull(chatBatch, "chatBatch");
        Objects.requireNonNull(playerFacts, "playerFacts");
        Objects.requireNonNull(worldTransition, "worldTransition");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        scoreboardLines = copyStrings(scoreboardLines);
        tabLines = copyStrings(tabLines);
        tabFooter = copyStrings(tabFooter);
        vacuumLore = copyStrings(vacuumLore);
        chatBatch = chatBatch.map(List::copyOf);
    }

    private static Observation<List<String>> copyStrings(Observation<List<String>> source) {
        return source.map(lines -> {
            Objects.requireNonNull(lines, "lines");
            for (String line : lines) {
                Objects.requireNonNull(line, "line");
            }
            return List.copyOf(lines);
        });
    }

    public static RawGameTextSnapshot unknown(long generation) {
        return new RawGameTextSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                PlayerFacts.unknown(), Observation.unknown(), generation);
    }
}
