package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import dev.hylfrd.farmhelper.runtime.gamestate.RawChatMessage;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Client-thread chat staging that rejects before retaining over-budget text. */
final class BoundedChatBuffer {
    private final ArrayDeque<RawChatMessage> messages = new ArrayDeque<>();
    private long nextSequence;
    private int characters;
    private boolean overflow;

    void accept(String channel, String text) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(text, "text");
        long messageCharacters = (long) channel.length() + text.length();
        if (nextSequence == Long.MAX_VALUE
                || channel.length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                || text.length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                || messageCharacters > GameTextInputBudget.MAX_TOTAL_CHARACTERS
                || characters > GameTextInputBudget.MAX_TOTAL_CHARACTERS - messageCharacters
                || messages.size() >= GameTextInputBudget.MAX_CHAT_MESSAGES) {
            overflow();
            return;
        }
        messages.addLast(new RawChatMessage(nextSequence++, channel, text));
        characters += (int) messageCharacters;
    }

    void overflow() {
        overflow = true;
    }

    Observation<List<RawChatMessage>> drain() {
        if (overflow) {
            reset();
            return Observation.unknown();
        }
        List<RawChatMessage> drained = List.copyOf(messages);
        messages.clear();
        characters = 0;
        return Observation.present(drained);
    }

    void reset() {
        messages.clear();
        characters = 0;
        overflow = false;
    }
}
