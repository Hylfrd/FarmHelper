package dev.hylfrd.farmhelper.runtime.gamestate;

import java.util.Objects;

/** One ordered, immutable chat input. */
public record RawChatMessage(long sequence, String channel, String text) {
    public RawChatMessage(long sequence, String text) {
        this(sequence, "system", text);
    }

    public RawChatMessage {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(text, "text");
    }
}
