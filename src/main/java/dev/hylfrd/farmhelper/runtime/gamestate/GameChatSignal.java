package dev.hylfrd.farmhelper.runtime.gamestate;

import java.util.Objects;

/** A typed chat conversion which deliberately excludes the original message and identity. */
public record GameChatSignal(long sequence, GameChatSignalType type) {
    public GameChatSignal {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(type, "type");
    }
}
