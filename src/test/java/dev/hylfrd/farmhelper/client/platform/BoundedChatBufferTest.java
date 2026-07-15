package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedChatBufferTest {
    @Test
    void rejectsAnOversizedMessageBeforePublishingAnyPartialBatch() {
        BoundedChatBuffer buffer = new BoundedChatBuffer();
        buffer.accept("chat", "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS + 1));

        assertTrue(buffer.drain().isUnknown());
        assertEquals(0, buffer.drain().get().size());
    }

    @Test
    void rejectsAValidLineSequenceWhoseCumulativeCharactersOverflow() {
        BoundedChatBuffer buffer = new BoundedChatBuffer();
        String line = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS);
        for (int index = 0; index < 40; index++) {
            buffer.accept("chat", line);
        }

        assertTrue(buffer.drain().isUnknown());
    }
}
