package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.List;
import java.util.Objects;

/** Immutable state, chat conversions, and privacy-safe diagnostics from one parse. */
public record GameStateParseResult(
        GameStateSnapshot snapshot,
        Observation<List<GameChatSignal>> chatSignals,
        List<ParseDiagnostic> diagnostics
) {
    public GameStateParseResult {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(chatSignals, "chatSignals");
        Objects.requireNonNull(diagnostics, "diagnostics");
        chatSignals = chatSignals.map(List::copyOf);
        diagnostics = List.copyOf(diagnostics);
    }
}
