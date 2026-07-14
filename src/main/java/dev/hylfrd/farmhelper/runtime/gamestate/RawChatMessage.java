package dev.hylfrd.farmhelper.runtime.gamestate;

import java.util.Objects;

/** One ordered, immutable system-chat input. */
public record RawChatMessage(long sequence, String text) {
    public RawChatMessage {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(text, "text");
    }
}
