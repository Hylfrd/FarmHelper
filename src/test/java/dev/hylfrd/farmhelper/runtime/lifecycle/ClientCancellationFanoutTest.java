package dev.hylfrd.farmhelper.runtime.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientCancellationFanoutTest {
    @Test
    void oneFailureCannotPreventAnyLaterOwnerFromReleasing() {
        List<String> attempts = new ArrayList<>();
        ClientCancellationFanout fanout = new ClientCancellationFanout(
                reason -> attempts.add("macro"),
                reason -> {
                    attempts.add("queue");
                    throw new IllegalStateException("queue failed");
                },
                reason -> attempts.add("inventory"),
                reason -> {
                    attempts.add("rotation");
                    throw new AssertionError("rotation failed");
                },
                reason -> attempts.add("input"));

        RuntimeException failure = fanout.cancel(ClientCancellationReason.EXCEPTION).orElseThrow();

        assertEquals(List.of("macro", "queue", "inventory", "rotation", "input"), attempts);
        assertEquals(2, failure.getSuppressed().length);
    }
}
